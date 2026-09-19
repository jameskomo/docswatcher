package dev.docswatcher.app.github;

import dev.docswatcher.app.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnExpression("'${docswatcher.github.app-id:}' != ''")
public class RestGitHubClient implements GitHubClient {

  private record Token(String value, Instant expiresAt) {}

  private final RestClient rest;
  private final GitHubAppJwt jwt;
  private final Map<Long, Token> tokens = new ConcurrentHashMap<>();

  public RestGitHubClient(AppProperties properties, RestClient.Builder builder) {
    AppProperties.GitHub gh = properties.github();
    this.rest = builder.baseUrl(gh.apiBase()).defaultHeader("Accept", "application/vnd.github+json").defaultHeader("X-GitHub-Api-Version", "2022-11-28").build();
    this.jwt = new GitHubAppJwt(gh.appId(), gh.privateKey());
  }

  private String installationToken(long installationId) {
    Token cached = tokens.get(installationId);
    if (cached != null && cached.expiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(2)))) {
      return cached.value();
    }
    Map<?, ?> body =
        rest.post()
            .uri("/app/installations/{id}/access_tokens", installationId)
            .header("Authorization", "Bearer " + jwt.sign(Instant.now()))
            .retrieve()
            .body(Map.class);
    String token = (String) body.get("token");
    tokens.put(installationId, new Token(token, Instant.parse((String) body.get("expires_at"))));
    return token;
  }

  private RestClient.RequestBodySpec post(long installationId, String uri, Object... vars) {
    return rest.post().uri(uri, vars).header("Authorization", "Bearer " + installationToken(installationId));
  }

  @Override
  public List<InstallationRepo> listInstallationRepos(long installationId) {
    List<InstallationRepo> out = new ArrayList<>();
    for (int page = 1; ; page++) {
      Map<?, ?> body =
          rest.get()
              .uri("/installation/repositories?per_page=100&page={p}", page)
              .header("Authorization", "Bearer " + installationToken(installationId))
              .retrieve()
              .body(Map.class);
      List<?> repos = (List<?>) body.get("repositories");
      for (Object o : repos) {
        Map<?, ?> r = (Map<?, ?>) o;
        out.add(new InstallationRepo(((Number) r.get("id")).longValue(), (String) r.get("full_name"), (String) r.get("default_branch")));
      }
      if (repos.size() < 100) {
        return out;
      }
    }
  }

  @Override
  public CloneSource cloneSource(long installationId, String fullName) {
    return new CloneSource("https://github.com/" + fullName + ".git", "x-access-token", installationToken(installationId));
  }

  @Override
  public void createCheckRun(long installationId, String fullName, String headSha, String name, String conclusion, String title, String summary) {
    post(installationId, "/repos/{repo}/check-runs", fullName)
        .body(Map.of("name", name, "head_sha", headSha, "status", "completed", "conclusion", conclusion, "output", Map.of("title", title, "summary", summary)))
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public int createIssue(long installationId, String fullName, String title, String body, List<String> labels) {
    Map<?, ?> created =
        post(installationId, "/repos/{repo}/issues", fullName)
            .body(Map.of("title", title, "body", body, "labels", labels))
            .retrieve()
            .body(Map.class);
    return ((Number) created.get("number")).intValue();
  }

  @Override
  public void closeIssue(long installationId, String fullName, int issueNumber, String comment) {
    post(installationId, "/repos/{repo}/issues/{n}/comments", fullName, issueNumber).body(Map.of("body", comment)).retrieve().toBodilessEntity();
    rest.patch()
        .uri("/repos/{repo}/issues/{n}", fullName, issueNumber)
        .header("Authorization", "Bearer " + installationToken(installationId))
        .body(Map.of("state", "closed", "state_reason", "completed"))
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public void addLabels(long installationId, String fullName, int issueNumber, List<String> labels) {
    post(installationId, "/repos/{repo}/issues/{n}/labels", fullName, issueNumber).body(Map.of("labels", labels)).retrieve().toBodilessEntity();
  }

  @Override
  public void repositoryDispatch(long installationId, String fullName, String eventType, Map<String, Object> clientPayload) {
    post(installationId, "/repos/{repo}/dispatches", fullName).body(Map.of("event_type", eventType, "client_payload", clientPayload)).retrieve().toBodilessEntity();
  }
}
