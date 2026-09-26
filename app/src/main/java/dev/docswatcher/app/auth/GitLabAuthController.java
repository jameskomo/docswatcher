package dev.docswatcher.app.auth;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.gitlab.GitLabApi;
import dev.docswatcher.app.gitlab.GitLabProperties;
import dev.docswatcher.app.gitlab.GitLabStore;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sign-in with GitLab, the counterpart of {@link AuthController} (docs/adr/0011-gitlab.md).
 *
 * <p>The same flow: a random state and a PKCE S256 challenge remembered in a short-lived
 * {@code __Host-} cookie, a callback that refuses a state it did not issue, a code exchange, and a
 * session whose authorisation is a snapshot of what GitLab says the person can reach. The OAuth
 * token asks for {@code read_api} only, is used during the callback, and is dropped.
 */
@RestController
public class GitLabAuthController {

  private static final Logger log = LoggerFactory.getLogger(GitLabAuthController.class);

  /** Reading is enough: sign-in only asks who the person is and what they can see. */
  static final String SCOPE = "read_api";

  private static final Duration STATE_TTL = AuthController.STATE_TTL;

  private final GitLabProperties gitlab;
  private final String webOrigin;
  private final GitLabApi api;
  private final GitLabStore store;
  private final SessionStore sessions;

  public GitLabAuthController(GitLabProperties gitlab, AppProperties properties, GitLabApi api, GitLabStore store, SessionStore sessions) {
    this.gitlab = gitlab;
    this.webOrigin = Cookies.stripSlash(properties.web().origin());
    this.api = api;
    this.store = store;
    this.sessions = sessions;
  }

  String callbackUrl() {
    return webOrigin + "/auth/gitlab/callback";
  }

  @GetMapping("/auth/gitlab/login")
  public ResponseEntity<String> login() {
    if (!gitlab.signInEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN)
          .body("Sign-in with GitLab is not configured on this deployment.");
    }
    String state = SessionStore.randomToken();
    String verifier = SessionStore.randomToken();
    URI authorize = UriComponentsBuilder.fromUriString(gitlab.baseUrl() + "/oauth/authorize")
        .queryParam("client_id", gitlab.clientId())
        .queryParam("redirect_uri", callbackUrl())
        .queryParam("response_type", "code")
        .queryParam("scope", SCOPE)
        .queryParam("state", state)
        .queryParam("code_challenge", AuthController.challenge(verifier))
        .queryParam("code_challenge_method", "S256")
        .encode()
        .build()
        .toUri();
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(authorize)
        .header(HttpHeaders.SET_COOKIE, Cookies.set(Cookies.OAUTH_GITLAB, state + "." + verifier, STATE_TTL))
        .cacheControl(CacheControl.noStore())
        .build();
  }

  @GetMapping("/auth/gitlab/callback")
  public ResponseEntity<Void> callback(
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "error", required = false) String error,
      HttpServletRequest request) {
    String remembered = Cookies.read(request, Cookies.OAUTH_GITLAB);
    if (!gitlab.signInEnabled()) {
      return back("unavailable", null);
    }
    if (error != null) {
      return back("denied", null);
    }
    int dot = remembered == null ? -1 : remembered.indexOf('.');
    if (dot < 0 || state == null || code == null || !AuthController.constantTimeEquals(remembered.substring(0, dot), state)) {
      log.warn("GitLab sign-in callback refused: the state did not match the cookie that started it");
      return back("failed", null);
    }
    String verifier = remembered.substring(dot + 1);
    try {
      String token = api.exchangeCode(code, verifier, callbackUrl());
      GitLabApi.User user = api.user(token);
      List<UserSession.OrgAccess> access = access(token);
      sessions.purgeExpired();
      sessions.delete(Cookies.read(request, Cookies.SESSION));
      String session = sessions.create(user.id(), user.username(), user.name(), user.avatarUrl(), access, gitlab.sessionTtl(), UserSession.GITLAB);
      log.info("{} signed in with GitLab with access to {} group(s)", user.username(), access.size());
      return back(null, Cookies.set(Cookies.SESSION, session, gitlab.sessionTtl()));
    } catch (GitLabApi.GitLabException e) {
      log.warn("GitLab sign-in failed: {}", e.getMessage());
      return back("failed", null);
    }
  }

  /**
   * The connected projects the person is a member of, with the role GitLab gives them there,
   * grouped by connection. Reporter reads (Guests cannot read a private project's code, which is
   * what a finding's evidence is), Developer writes, Maintainer maintains: the same bars as
   * GitHub's read, write and maintain. Nothing is asked when nothing is connected.
   */
  List<UserSession.OrgAccess> access(String token) {
    List<GitLabStore.HeldProject> held = store.heldProjects();
    if (held.isEmpty()) {
      return List.of();
    }
    Set<Long> read = api.projectIdsAtLeast(token, GitLabApi.REPORTER);
    if (read.isEmpty()) {
      return List.of();
    }
    Set<Long> write = api.projectIdsAtLeast(token, GitLabApi.DEVELOPER);
    Set<Long> maintain = write.isEmpty() ? Set.of() : api.projectIdsAtLeast(token, GitLabApi.MAINTAINER);
    Map<Long, List<UserSession.RepoAccess>> byConnection = new LinkedHashMap<>();
    Map<Long, String> logins = new LinkedHashMap<>();
    for (GitLabStore.HeldProject h : held) {
      if (!read.contains(h.projectId())) {
        continue;
      }
      String permission = maintain.contains(h.projectId()) ? "maintain" : write.contains(h.projectId()) ? "write" : "read";
      byConnection.computeIfAbsent(h.installationId(), k -> new ArrayList<>()).add(new UserSession.RepoAccess(h.repoId(), h.fullName(), permission));
      logins.put(h.installationId(), h.login());
    }
    List<UserSession.OrgAccess> out = new ArrayList<>();
    byConnection.forEach((id, repos) -> out.add(new UserSession.OrgAccess(id, logins.get(id), repos)));
    return out;
  }

  private ResponseEntity<Void> back(String problem, String sessionCookie) {
    return AuthController.back(webOrigin, Cookies.OAUTH_GITLAB, problem, sessionCookie);
  }
}
