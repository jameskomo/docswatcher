package dev.docswatcher.app.github;

import dev.docswatcher.app.auth.OAuthProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * GitHub, as seen by a signed-in person rather than by the App.
 *
 * <p>Everything here runs with a user-to-server token: the code exchange that produces it, then
 * the three questions sign-in needs answered. Who is this, which installations of this App can
 * they reach, and which repositories inside each one, at what permission. GitHub answers the last
 * two from the intersection of what the App was granted and what the person can see, so the
 * answer is already the authorisation DocsWatcher needs; nothing here second-guesses it.
 *
 * <p>The token is used during sign-in and then dropped. It is never stored (ADR 0008).
 */
@Component
public class GitHubUserApi {

  /** Pages of 100. A cap, so an unexpectedly huge account cannot hold a sign-in open for minutes. */
  static final int PER_PAGE = 100;
  static final int MAX_PAGES = 50;

  public record User(long id, String login, String name, String avatarUrl) {}

  public record UserInstallation(long id, String accountLogin) {}

  /** A repository the person can reach through one installation, with their own permission on it. */
  public record UserRepo(long id, String fullName, String permission) {}

  /** A failed exchange or lookup. The message is for the log, never for the browser. */
  public static class GitHubAuthException extends RuntimeException {
    public GitHubAuthException(String message) {
      super(message);
    }

    public GitHubAuthException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final RestClient rest;
  private final OAuthProperties oauth;

  public GitHubUserApi(OAuthProperties oauth, RestClient.Builder builder) {
    this.oauth = oauth;
    this.rest = builder.build();
  }

  /**
   * Trades the authorization code for a user access token. The PKCE verifier proves this is the
   * same party that started the flow; GitHub answers errors with a 200 and an {@code error} field,
   * so the body decides success, not the status.
   */
  public String exchangeCode(String code, String codeVerifier, String redirectUri) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("client_id", oauth.clientId());
    form.add("client_secret", oauth.clientSecret());
    form.add("code", code);
    form.add("redirect_uri", redirectUri);
    form.add("code_verifier", codeVerifier);
    Map<?, ?> body;
    try {
      body = rest.post()
          .uri(oauth.webBase() + "/login/oauth/access_token")
          .accept(MediaType.APPLICATION_JSON)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(Map.class);
    } catch (RestClientException e) {
      throw new GitHubAuthException("code exchange failed", e);
    }
    if (body == null || !(body.get("access_token") instanceof String token) || token.isBlank()) {
      Object error = body == null ? "empty response" : body.get("error");
      throw new GitHubAuthException("code exchange refused: " + error);
    }
    return token;
  }

  public User user(String token) {
    Map<?, ?> body = get(token, "/user");
    return new User(number(body.get("id")), (String) body.get("login"), (String) body.get("name"), (String) body.get("avatar_url"));
  }

  public List<UserInstallation> installations(String token) {
    List<UserInstallation> out = new ArrayList<>();
    for (Map<?, ?> item : paged(token, "/user/installations", "installations")) {
      Map<?, ?> account = (Map<?, ?>) item.get("account");
      out.add(new UserInstallation(number(item.get("id")), account == null ? null : (String) account.get("login")));
    }
    return out;
  }

  public List<UserRepo> repositories(String token, long installationId) {
    List<UserRepo> out = new ArrayList<>();
    for (Map<?, ?> item : paged(token, "/user/installations/" + installationId + "/repositories", "repositories")) {
      out.add(new UserRepo(number(item.get("id")), (String) item.get("full_name"), permission((Map<?, ?>) item.get("permissions"))));
    }
    return out;
  }

  /** GitHub's per-repository booleans, reduced to the highest level the person holds. */
  static String permission(Map<?, ?> permissions) {
    if (permissions == null) {
      return "read";
    }
    for (String[] level : new String[][] {{"admin", "admin"}, {"maintain", "maintain"}, {"push", "write"}, {"triage", "triage"}, {"pull", "read"}}) {
      if (Boolean.TRUE.equals(permissions.get(level[0]))) {
        return level[1];
      }
    }
    return "none";
  }

  private List<Map<?, ?>> paged(String token, String path, String field) {
    List<Map<?, ?>> out = new ArrayList<>();
    for (int page = 1; page <= MAX_PAGES; page++) {
      Map<?, ?> body = get(token, path + "?per_page=" + PER_PAGE + "&page=" + page);
      List<?> items = body.get(field) instanceof List<?> l ? l : List.of();
      for (Object item : items) {
        out.add((Map<?, ?>) item);
      }
      long total = body.get("total_count") instanceof Number n ? n.longValue() : out.size();
      if (items.size() < PER_PAGE || out.size() >= total) {
        break;
      }
    }
    return out;
  }

  private Map<?, ?> get(String token, String path) {
    try {
      Map<?, ?> body = rest.get()
          .uri(oauth.apiBase() + path)
          .header("Authorization", "Bearer " + token)
          .header("Accept", "application/vnd.github+json")
          .header("X-GitHub-Api-Version", RestGitHubClient.API_VERSION)
          .retrieve()
          .body(Map.class);
      if (body == null) {
        throw new GitHubAuthException("empty response from " + path);
      }
      return body;
    } catch (RestClientException e) {
      throw new GitHubAuthException("GET " + path + " failed", e);
    }
  }

  private static long number(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    throw new GitHubAuthException("expected a number, got " + value);
  }
}
