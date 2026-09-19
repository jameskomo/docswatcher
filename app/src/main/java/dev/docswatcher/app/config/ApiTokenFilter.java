package dev.docswatcher.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Shared bearer token for the dashboard API. Not a user identity; that comes in v1.5 with GitHub login. */
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

  private final AppProperties.Api api;

  public ApiTokenFilter(AppProperties properties) {
    this.api = properties.api();
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
    if (!api.requireToken()) {
      chain.doFilter(request, response);
      return;
    }
    if (api.token() == null || api.token().isBlank()) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "DOCSWATCHER_API_TOKEN is not set");
      return;
    }
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ") || !constantTimeEquals(header.substring(7), api.token())) {
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    chain.doFilter(request, response);
  }

  static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
