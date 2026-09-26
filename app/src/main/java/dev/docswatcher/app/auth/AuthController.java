package dev.docswatcher.app.auth;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.github.GitHubUserApi;
import dev.docswatcher.app.gitlab.GitLabProperties;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.Repo;
import dev.docswatcher.app.store.RepoStore;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sign-in with GitHub, through the GitHub App's own OAuth client. docs/adr/0008-sign-in-with-github.md.
 *
 * <p>{@code /auth/github/login} sends the browser to GitHub with a fresh state and a PKCE
 * challenge, both remembered in a short-lived cookie. {@code /auth/github/callback} checks the
 * state against that cookie, exchanges the code, asks GitHub which installations and repositories
 * the person can reach, keeps the ones this deployment holds, and starts a session. The user token
 * is then dropped. Everything the browser is sent back to is a fixed page on the site, never a URL
 * from the request, so there is no open redirect to abuse.
 */
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  /** Long enough to sign in and approve the App, short enough that a stale state is useless. */
  static final Duration STATE_TTL = Duration.ofMinutes(10);

  private final OAuthProperties oauth;
  private final String webOrigin;
  private final GitHubUserApi github;
  private final SessionStore sessions;
  private final InstallationStore installations;
  private final RepoStore repos;
  private final GitLabProperties gitlab;

  public AuthController(OAuthProperties oauth, AppProperties properties, GitHubUserApi github, SessionStore sessions,
      InstallationStore installations, RepoStore repos, GitLabProperties gitlab) {
    this.oauth = oauth;
    this.gitlab = gitlab;
    this.webOrigin = Cookies.stripSlash(properties.web().origin());
    this.github = github;
    this.sessions = sessions;
    this.installations = installations;
    this.repos = repos;
  }

  String callbackUrl() {
    return webOrigin + "/auth/github/callback";
  }

  @GetMapping("/auth/github/login")
  public ResponseEntity<String> login() {
    if (!oauth.enabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.TEXT_PLAIN)
          .body("Sign-in with GitHub is not configured on this deployment.");
    }
    String state = SessionStore.randomToken();
    String verifier = SessionStore.randomToken();
    URI authorize = UriComponentsBuilder.fromUriString(oauth.webBase() + "/login/oauth/authorize")
        .queryParam("client_id", oauth.clientId())
        .queryParam("redirect_uri", callbackUrl())
        .queryParam("state", state)
        .queryParam("code_challenge", challenge(verifier))
        .queryParam("code_challenge_method", "S256")
        .encode()
        .build()
        .toUri();
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(authorize)
        .header(HttpHeaders.SET_COOKIE, Cookies.set(Cookies.OAUTH, state + "." + verifier, STATE_TTL))
        .cacheControl(CacheControl.noStore())
        .build();
  }

  @GetMapping("/auth/github/callback")
  public ResponseEntity<Void> callback(
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "error", required = false) String error,
      HttpServletRequest request) {
    String remembered = Cookies.read(request, Cookies.OAUTH);
    if (!oauth.enabled()) {
      return back("unavailable", null);
    }
    if (error != null) {
      // The person pressed Cancel on GitHub, or GitHub refused. Not an attack, just not a sign-in.
      return back("denied", null);
    }
    int dot = remembered == null ? -1 : remembered.indexOf('.');
    if (dot < 0 || state == null || code == null || !constantTimeEquals(remembered.substring(0, dot), state)) {
      log.warn("Sign-in callback refused: the state did not match the cookie that started it");
      return back("failed", null);
    }
    String verifier = remembered.substring(dot + 1);
    try {
      String token = github.exchangeCode(code, verifier, callbackUrl());
      GitHubUserApi.User user = github.user(token);
      List<UserSession.OrgAccess> access = access(token);
      sessions.purgeExpired();
      // A sign-in always starts a new session; whatever the browser held before is ended, not reused.
      sessions.delete(Cookies.read(request, Cookies.SESSION));
      String session = sessions.create(user.id(), user.login(), user.name(), user.avatarUrl(), access, oauth.sessionTtl());
      log.info("{} signed in with access to {} organisation(s)", user.login(), access.size());
      return back(null, Cookies.set(Cookies.SESSION, session, oauth.sessionTtl()));
    } catch (GitHubUserApi.GitHubAuthException e) {
      log.warn("Sign-in failed: {}", e.getMessage());
      return back("failed", null);
    }
  }

  /**
   * What GitHub says the person can reach, narrowed to what this deployment holds. An installation
   * only counts if its webhook reached us, and within it only stored repositories count, so the
   * session is small and never names a repository the dashboard could not show anyway.
   */
  private List<UserSession.OrgAccess> access(String token) {
    List<UserSession.OrgAccess> out = new ArrayList<>();
    for (GitHubUserApi.UserInstallation inst : github.installations(token)) {
      Optional<dev.docswatcher.app.store.Installation> stored = installations.find(inst.id());
      if (stored.isEmpty()) {
        continue;
      }
      Map<Long, Repo> held = new HashMap<>();
      for (Repo r : repos.forInstallation(inst.id())) {
        held.put(r.id(), r);
      }
      List<UserSession.RepoAccess> visible = new ArrayList<>();
      for (GitHubUserApi.UserRepo r : github.repositories(token, inst.id())) {
        Repo repo = held.get(r.id());
        if (repo != null && !"none".equals(r.permission())) {
          visible.add(new UserSession.RepoAccess(repo.id(), repo.fullName(), r.permission()));
        }
      }
      out.add(new UserSession.OrgAccess(inst.id(), stored.get().accountLogin(), visible));
    }
    return out;
  }

  /** Back to the dashboard, optionally saying why sign-in did not happen. Always clears the state cookie. */
  private ResponseEntity<Void> back(String problem, String sessionCookie) {
    return back(webOrigin, Cookies.OAUTH, problem, sessionCookie);
  }

  /** The same for any provider's callback, clearing that provider's state cookie. */
  static ResponseEntity<Void> back(String webOrigin, String stateCookie, String problem, String sessionCookie) {
    String target = webOrigin + "/#/app" + (problem == null ? "" : "?signin=" + problem);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(target))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.SET_COOKIE, Cookies.clear(stateCookie));
    if (sessionCookie != null) {
      response.header(HttpHeaders.SET_COOKIE, sessionCookie);
    }
    return response.build();
  }

  /**
   * Who is signed in, for the dashboard. Answers 200 either way, so a signed-out visitor is a
   * state rather than an error, and says whether sign-in exists here at all.
   */
  @GetMapping("/auth/me")
  public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    // enabled: any sign-in exists here. providers: which ones, so the site offers each (ADR 0012).
    body.put("enabled", oauth.enabled() || gitlab.signInEnabled());
    Map<String, Object> providers = new LinkedHashMap<>();
    providers.put("github", oauth.enabled());
    providers.put("gitlab", gitlab.signInEnabled());
    body.put("providers", providers);
    Optional<UserSession> session = sessions.find(Cookies.read(request, Cookies.SESSION));
    body.put("signedIn", session.isPresent());
    session.ifPresent(s -> {
      Map<String, Object> user = new LinkedHashMap<>();
      user.put("login", s.login());
      user.put("name", s.name());
      user.put("avatarUrl", s.avatarUrl());
      body.put("user", user);
      body.put("provider", s.provider());
      List<Map<String, Object>> orgs = new ArrayList<>();
      for (UserSession.OrgAccess o : s.orgs()) {
        Map<String, Object> org = new LinkedHashMap<>();
        org.put("login", o.login());
        org.put("installationId", o.installationId());
        org.put("repos", o.repos().size());
        orgs.add(org);
      }
      body.put("orgs", orgs);
      body.put("expiresAt", s.expiresAt().toString());
    });
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  /** POST, and only from the site itself, so another page cannot sign a visitor out. */
  @PostMapping("/auth/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    if (!Cookies.sameOrigin(request, webOrigin)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    sessions.delete(Cookies.read(request, Cookies.SESSION));
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, Cookies.clear(Cookies.SESSION)).build();
  }

  /** RFC 7636 S256: the base64url SHA-256 of the verifier. */
  static String challenge(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
