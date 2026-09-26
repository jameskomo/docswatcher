package dev.docswatcher.app.gitlab;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Everything DocsWatcher asks GitLab, over REST API v4 (docs/adr/0011-gitlab.md).
 *
 * <p>Stateless: every call takes the token it spends. Sign-in passes the person's OAuth token,
 * which is used during the callback and dropped; scans and webhooks pass the connection's access
 * token, decrypted for the call. Nothing here stores either.
 *
 * <p>A path such as {@code acme/platform} goes out as one URI variable, which RestClient encodes
 * strictly into {@code acme%2Fplatform}: exactly the form GitLab's API expects for a full path.
 */
@Component
public class GitLabApi {

  /** Pages of 100, and a cap, so an unexpectedly huge account cannot hold a request open for minutes. */
  static final int PER_PAGE = 100;
  static final int MAX_PAGES = 50;

  /** GitLab's roles, as the members API reports them. */
  public static final int REPORTER = 20;
  public static final int DEVELOPER = 30;
  public static final int MAINTAINER = 40;

  public record User(long id, String username, String name, String avatarUrl) {}

  /** What GitLab says about the token itself: whose it is, what it may do, until when. */
  public record TokenInfo(long userId, List<String> scopes, boolean active, LocalDate expiresAt) {}

  /** A group or a project, as a connection names it. {@code kind} is "group" or "project". */
  public record Namespace(String kind, long id, String fullPath) {}

  /** A project; {@code defaultBranch} is null for an empty repository, which has nothing to scan. */
  public record Project(long id, String pathWithNamespace, String defaultBranch) {}

  /** A failed call. {@code status} is GitLab's HTTP status, or 0 when none came back. The message is for the log. */
  public static class GitLabException extends RuntimeException {
    private final int status;

    public GitLabException(String message, int status, Throwable cause) {
      super(message, cause);
      this.status = status;
    }

    public int status() {
      return status;
    }
  }

  private final GitLabProperties properties;
  private final RestClient rest;

  public GitLabApi(GitLabProperties properties, RestClient.Builder builder) {
    this.properties = properties;
    this.rest = builder.build();
  }

  public String baseUrl() {
    return properties.baseUrl();
  }

  /** Where a project is cloned from over HTTPS. Built from the configured instance, never from a payload. */
  public String cloneUri(String pathWithNamespace) {
    return properties.baseUrl() + "/" + pathWithNamespace + ".git";
  }

  // --- Sign-in ---------------------------------------------------------------------------------

  /** Trades an authorization code for the person's access token, proving the flow with the PKCE verifier. */
  public String exchangeCode(String code, String codeVerifier, String redirectUri) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", properties.clientId());
    form.add("client_secret", properties.clientSecret());
    form.add("code", code);
    form.add("grant_type", "authorization_code");
    form.add("redirect_uri", redirectUri);
    form.add("code_verifier", codeVerifier);
    Map<?, ?> body;
    try {
      body = rest.post()
          .uri(properties.baseUrl() + "/oauth/token")
          .accept(MediaType.APPLICATION_JSON)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(Map.class);
    } catch (RestClientException e) {
      throw failure("code exchange failed", e);
    }
    if (body == null || !(body.get("access_token") instanceof String token) || token.isBlank()) {
      throw new GitLabException("code exchange refused: " + (body == null ? "empty response" : body.get("error")), 0, null);
    }
    return token;
  }

  public User user(String token) {
    Map<?, ?> body = get(token, "/user");
    return new User(number(body.get("id")), (String) body.get("username"), (String) body.get("name"), (String) body.get("avatar_url"));
  }

  /**
   * The ids of every project the person is a member of at {@code minAccessLevel} or above, directly
   * or through a group. GitLab computes the effective level, inherited membership included.
   */
  public Set<Long> projectIdsAtLeast(String token, int minAccessLevel) {
    Set<Long> out = new HashSet<>();
    for (Map<?, ?> p : paged(token, "/projects?membership=true&simple=true&min_access_level=" + minAccessLevel)) {
      out.add(number(p.get("id")));
    }
    return out;
  }

  // --- Connections -----------------------------------------------------------------------------

  /** Works for personal, group and project access tokens alike: the latter two are tokens of a bot user. */
  public TokenInfo tokenSelf(String token) {
    Map<?, ?> body = get(token, "/personal_access_tokens/self");
    List<String> scopes = new ArrayList<>();
    if (body.get("scopes") instanceof List<?> l) {
      l.forEach(s -> scopes.add(String.valueOf(s)));
    }
    boolean active = Boolean.TRUE.equals(body.get("active")) && !Boolean.TRUE.equals(body.get("revoked"));
    LocalDate expires = body.get("expires_at") instanceof String s && !s.isBlank() ? LocalDate.parse(s) : null;
    return new TokenInfo(number(body.get("user_id")), List.copyOf(scopes), active, expires);
  }

  public Optional<Namespace> group(String token, String fullPath) {
    return optional(() -> {
      Map<?, ?> body = get(token, "/groups/{path}?with_projects=false", fullPath);
      return new Namespace("group", number(body.get("id")), (String) body.get("full_path"));
    });
  }

  public Optional<Project> project(String token, Object idOrPath) {
    return optional(() -> toProject(get(token, "/projects/{id}", idOrPath)));
  }

  /** The group's projects, subgroups included, archived ones left out. */
  public List<Project> groupProjects(String token, long groupId) {
    List<Project> out = new ArrayList<>();
    for (Map<?, ?> p : paged(token, "/groups/" + groupId + "/projects?include_subgroups=true&with_shared=false&archived=false&simple=true")) {
      out.add(toProject(p));
    }
    return out;
  }

  /**
   * A user's effective role in a group or project, inherited membership included, or 0 when they
   * have none. {@code kind} is "group" or "project".
   */
  public int accessLevel(String token, String kind, long namespaceId, long userId) {
    String collection = "group".equals(kind) ? "groups" : "projects";
    try {
      Map<?, ?> body = get(token, "/" + collection + "/" + namespaceId + "/members/all/" + userId);
      return body.get("access_level") instanceof Number n ? n.intValue() : 0;
    } catch (GitLabException e) {
      if (e.status() == HttpStatus.NOT_FOUND.value()) {
        return 0;
      }
      throw e;
    }
  }

  // --- What a scan says back -------------------------------------------------------------------

  /** Opens an issue and returns its iid, the number people see in the project. */
  public int createIssue(String token, long projectId, String title, String description, List<String> labels) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("description", description);
    body.put("labels", String.join(",", labels));
    Map<?, ?> created = send(token, "POST", "/projects/" + projectId + "/issues", body);
    return (int) number(created.get("iid"));
  }

  /** Says why, then closes. */
  public void closeIssue(String token, long projectId, int iid, String comment) {
    send(token, "POST", "/projects/" + projectId + "/issues/" + iid + "/notes", Map.of("body", comment));
    send(token, "PUT", "/projects/" + projectId + "/issues/" + iid, Map.of("state_event", "close"));
  }

  /** {@code state} is GitLab's: pending, running, success, failed or canceled. */
  public void commitStatus(String token, long projectId, String sha, String state, String name, String description, String targetUrl) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("state", state);
    body.put("name", name);
    body.put("description", description);
    if (targetUrl != null) {
      body.put("target_url", targetUrl);
    }
    send(token, "POST", "/projects/" + projectId + "/statuses/" + sha, body);
  }

  // --- Plumbing --------------------------------------------------------------------------------

  private static Project toProject(Map<?, ?> p) {
    Object branch = p.get("default_branch");
    return new Project(number(p.get("id")), (String) p.get("path_with_namespace"), branch instanceof String s && !s.isBlank() ? s : null);
  }

  private Map<?, ?> get(String token, String path, Object... vars) {
    try {
      Map<?, ?> body = rest.get()
          .uri(properties.apiBase() + path, vars)
          .header("Authorization", "Bearer " + token)
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .body(Map.class);
      if (body == null) {
        throw new GitLabException("empty response from GET " + path, 0, null);
      }
      return body;
    } catch (RestClientException e) {
      throw failure("GET " + path + " failed", e);
    }
  }

  private Map<?, ?> send(String token, String method, String path, Map<String, Object> body) {
    try {
      RestClient.RequestBodySpec spec = "PUT".equals(method) ? rest.put().uri(properties.apiBase() + path) : rest.post().uri(properties.apiBase() + path);
      Map<?, ?> answer = spec
          .header("Authorization", "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(Map.class);
      return answer == null ? Map.of() : answer;
    } catch (RestClientException e) {
      throw failure(method + " " + path + " failed", e);
    }
  }

  /** Follows GitLab's offset pagination: X-Next-Page while it has one, and a short page otherwise. */
  private List<Map<?, ?>> paged(String token, String path) {
    List<Map<?, ?>> out = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES; page++) {
      String uri = properties.apiBase() + path + "&per_page=" + PER_PAGE + "&page=" + page;
      ResponseEntity<List> answer;
      try {
        answer = rest.get().uri(uri).header("Authorization", "Bearer " + token).accept(MediaType.APPLICATION_JSON).retrieve().toEntity(List.class);
      } catch (RestClientException e) {
        throw failure("GET " + path + " failed", e);
      }
      List<?> items = answer.getBody() == null ? List.of() : answer.getBody();
      for (Object item : items) {
        out.add((Map<?, ?>) item);
      }
      String next = answer.getHeaders().getFirst("X-Next-Page");
      if (items.size() < PER_PAGE || (next != null && next.isBlank())) {
        break;
      }
    }
    return out;
  }

  private static <T> Optional<T> optional(java.util.function.Supplier<T> call) {
    try {
      return Optional.of(call.get());
    } catch (GitLabException e) {
      if (e.status() == HttpStatus.NOT_FOUND.value()) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private static GitLabException failure(String message, RestClientException e) {
    int status = e instanceof RestClientResponseException r ? r.getStatusCode().value() : 0;
    return new GitLabException(message + (status == 0 ? "" : " with " + status), status, e);
  }

  private static long number(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    throw new GitLabException("expected a number, got " + value, 0, null);
  }
}
