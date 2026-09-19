package dev.docswatcher.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared bearer token for the dashboard API. Not a user identity; that comes in v1.5 with GitHub login. */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

  private final AppProperties.Api api;

  public ApiTokenFilter(AppProperties properties) {
    this.api = properties.api();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/") || "OPTIONS".equals(request.getMethod());
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
