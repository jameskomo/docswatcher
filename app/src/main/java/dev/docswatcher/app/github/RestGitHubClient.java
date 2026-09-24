package dev.docswatcher.app.github;

import dev.docswatcher.app.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
// Both are required. With an app id but no key the constructor used to throw and
// the container crash-looped, which is the worst way to report a missing secret.
// Requiring both means a half-configured deployment starts and the fallback client
// says exactly what is absent.
@ConditionalOnExpression("'${docswatcher.github.app-id:}' != '' and '${docswatcher.github.private-key:}' != ''")
public class RestGitHubClient implements GitHubClient {

  /**
   * The REST API version every request pins. 2022-11-28 stops being served on 2028-03-10.
   * 2026-03-10's breaking changes were checked against every endpoint and field this class uses;
   * none of them apply (docs/10-reference.md, "GitHub REST API version").
   */
  static final String API_VERSION = "2026-03-10";

  private static final Set<String> PERMISSIONS = Set.of("admin", "maintain", "write", "triage", "read", "none");

  /**
   * How long before {@code expires_at} a cached token is replaced. A token handed to a clone has
   * to outlive the clone, so the margin is longer than a clone is allowed to take.
   */
  static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

  private record Token(String value, Instant expiresAt) {}

  private final RestClient rest;
  private final GitHubAppJwt jwt;
  private final Map<Long, Token> tokens = new ConcurrentHashMap<>();
  // Bumped by every eviction. A token minted while an eviction happened is used but not cached,
  // so a request that was already in flight cannot put back what the eviction removed.
  private long evictions;

  public RestGitHubClient(AppProperties properties, RestClient.Builder builder) {
    AppProperties.GitHub gh = properties.github();
    this.rest = builder.baseUrl(gh.apiBase()).defaultHeader("Accept", "application/vnd.github+json").defaultHeader("X-GitHub-Api-Version", API_VERSION).build();
    this.jwt = new GitHubAppJwt(gh.appId(), gh.privateKey());
  }

  private String installationToken(long installationId) {
    Token cached = tokens.get(installationId);
    if (cached != null && cached.expiresAt().isAfter(Instant.now().plus(REFRESH_MARGIN))) {
      return cached.value();
    }
    long generation = generation();
    Map<?, ?> body =
        rest.post()
            .uri("/app/installations/{id}/access_tokens", installationId)
            .header("Authorization", "Bearer " + jwt.sign(Instant.now()))
            .retrieve()
            .body(Map.class);
    String token = (String) body.get("token");
    Object expiresAt = body.get("expires_at");
    if (expiresAt instanceof String s) {
      cache(installationId, new Token(token, Instant.parse(s)), generation);
    }
    return token;
  }

  private synchronized long generation() {
    return evictions;
  }

  private synchronized void cache(long installationId, Token token, long generation) {
    if (generation == evictions) {
      tokens.put(installationId, token);
    }
  }

  @Override
  public synchronized void forgetInstallation(long installationId) {
    evictions++;
    tokens.remove(installationId);
  }

  private RestClient.RequestBodySpec post(long installationId, String uri, Object... vars) {
    return rest.post().uri(uri, vars).header("Authorization", "Bearer " + installationToken(installationId));
  }

  /**
   * URI variables for a "/repos/{owner}/{repo}/..." template, followed by {@code more}.
   *
   * <p>The full name cannot be one variable. RestClient encodes a variable's value strictly, so
   * "owner/repo" went out as "owner%2Frepo", and GitHub answers that path with a 404.
   */
  static Object[] repo(String fullName, Object... more) {
    int slash = fullName.indexOf('/');
    if (slash <= 0 || slash == fullName.length() - 1 || fullName.indexOf('/', slash + 1) >= 0) {
      throw new IllegalArgumentException("Not an owner/repo name: " + fullName);
    }
    Object[] vars = new Object[2 + more.length];
    vars[0] = fullName.substring(0, slash);
    vars[1] = fullName.substring(slash + 1);
    System.arraycopy(more, 0, vars, 2, more.length);
    return vars;
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
    post(installationId, "/repos/{owner}/{repo}/check-runs", repo(fullName))
        .body(Map.of("name", name, "head_sha", headSha, "status", "completed", "conclusion", conclusion, "output", Map.of("title", title, "summary", summary)))
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public int createIssue(long installationId, String fullName, String title, String body, List<String> labels) {
    Map<?, ?> created =
        post(installationId, "/repos/{owner}/{repo}/issues", repo(fullName))
            .body(Map.of("title", title, "body", body, "labels", labels))
            .retrieve()
            .body(Map.class);
    return ((Number) created.get("number")).intValue();
  }

  @Override
  public void closeIssue(long installationId, String fullName, int issueNumber, String comment) {
    post(installationId, "/repos/{owner}/{repo}/issues/{n}/comments", repo(fullName, issueNumber)).body(Map.of("body", comment)).retrieve().toBodilessEntity();
    rest.patch()
        .uri("/repos/{owner}/{repo}/issues/{n}", repo(fullName, issueNumber))
        .header("Authorization", "Bearer " + installationToken(installationId))
        .body(Map.of("state", "closed", "state_reason", "completed"))
        .retrieve()
        .toBodilessEntity();
  }

  @Override
  public void addLabels(long installationId, String fullName, int issueNumber, List<String> labels) {
    post(installationId, "/repos/{owner}/{repo}/issues/{n}/labels", repo(fullName, issueNumber)).body(Map.of("labels", labels)).retrieve().toBodilessEntity();
  }

  @Override
  public void repositoryDispatch(long installationId, String fullName, String eventType, Map<String, Object> clientPayload) {
    post(installationId, "/repos/{owner}/{repo}/dispatches", repo(fullName)).body(Map.of("event_type", eventType, "client_payload", clientPayload)).retrieve().toBodilessEntity();
  }

  @Override
  public String collaboratorPermission(long installationId, String fullName, String login) {
    try {
      Map<?, ?> body =
          rest.get()
              .uri("/repos/{owner}/{repo}/collaborators/{login}/permission", repo(fullName, login))
              .header("Authorization", "Bearer " + installationToken(installationId))
              .retrieve()
              .body(Map.class);
      Object permission = body == null ? null : body.get("permission");
      // Only a value from the documented set counts. A renamed field, a null or a shape we
      // do not know is an answer we did not get.
      return permission instanceof String p && PERMISSIONS.contains(p) ? p : "none";
    } catch (RuntimeException e) {
      // Fail closed. An answer we could not get is not authority.
      return "none";
    }
  }
}
