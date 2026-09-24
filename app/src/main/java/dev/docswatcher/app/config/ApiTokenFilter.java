package dev.docswatcher.app.config;

import dev.docswatcher.app.auth.AccessInterceptor;
import dev.docswatcher.app.auth.Cookies;
import dev.docswatcher.app.auth.SessionStore;
import dev.docswatcher.app.auth.UserSession;
import dev.docswatcher.app.auth.Viewer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Authentication for the dashboard API: who is asking, before any handler runs.
 *
 * <p>Two principals, never mixed. The shared bearer token is the deployment's owner and its
 * automation, and sees everything, as it always has. Without a token, a signed-in session cookie
 * makes the caller a member, who sees only what GitHub said they could when they signed in
 * ({@link AccessInterceptor} enforces that per organisation and per repository). A member's
 * state-changing requests must also come from the site's own origin. Neither: 401.
 *
 * <p>docs/adr/0008-sign-in-with-github.md.
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

  /**
   * Matched against the same parsed path Spring MVC routes on, where each segment is already
   * percent-decoded and stripped of its path parameters.
   *
   * <p>This used to test {@code getRequestURI().startsWith("/api/")}. That is the raw request line:
   * Tomcat leaves percent-escapes encoded and leaves {@code ;}-delimited path parameters attached,
   * while PathPatternParser matches on the decoded, de-parameterised segment value. The two
   * disagreed, so {@code /%61pi/...} and {@code /api;x=y/...} routed to an {@code /api} handler
   * while this filter saw "not an API request" and waved the call through. This filter is the only
   * authentication control in the application, so that was a full bypass rather than a gap.
   */
  private static final PathPattern API = PathPatternParser.defaultInstance.parse("/api/**");

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  private final AppProperties.Api api;
  private final SessionStore sessions;
  private final String webOrigin;

  public ApiTokenFilter(AppProperties properties, SessionStore sessions) {
    this.api = properties.api();
    this.sessions = sessions;
    this.webOrigin = properties.web().origin();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }
    return !isApiRequest(request);
  }

  /**
   * Fails closed. A request whose path cannot be parsed is treated as an API request and therefore
   * authenticated, rather than being forwarded because it did not look like one.
   *
   * <p>Uses {@code parse} rather than {@code parseAndCache} so the filter does not disturb the
   * request attribute DispatcherServlet manages for itself.
   */
  private static boolean isApiRequest(HttpServletRequest request) {
    try {
      PathContainer path = ServletRequestPathUtils.parse(request).pathWithinApplication();
      return API.matches(path);
    } catch (RuntimeException e) {
      return true;
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      // A caller that presents the token is judged on the token alone, never on a cookie too.
      if (!api.requireToken()) {
        pass(Viewer.OWNER, request, response, chain);
        return;
      }
      if (api.token() == null || api.token().isBlank()) {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "DOCSWATCHER_API_TOKEN is not set");
        return;
      }
      if (!header.startsWith("Bearer ") || !constantTimeEquals(header.substring(7), api.token())) {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
      pass(Viewer.OWNER, request, response, chain);
      return;
    }
    Optional<UserSession> session = sessions.find(Cookies.read(request, Cookies.SESSION));
    if (session.isPresent()) {
      if (!SAFE_METHODS.contains(request.getMethod()) && !Cookies.sameOrigin(request, webOrigin)) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-origin request refused");
        return;
      }
      pass(Viewer.Member.of(session.get()), request, response, chain);
      return;
    }
    if (!api.requireToken()) {
      // The dev profile: no token, no session, full access, as before sign-in existed.
      pass(Viewer.OWNER, request, response, chain);
      return;
    }
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
  }

  private static void pass(Viewer viewer, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    request.setAttribute(Viewer.ATTRIBUTE, viewer);
    chain.doFilter(request, response);
  }

  static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
