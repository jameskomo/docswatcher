package dev.docswatcher.app.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * The two cookies sign-in uses, and the one rule for writing them.
 *
 * <p>Both carry the {@code __Host-} prefix, which the browser enforces: Secure, Path=/, no Domain.
 * So neither can be set by a sibling subdomain or read over plain HTTP. Both are HttpOnly, so page
 * script never sees them, and SameSite=Lax, so a cross-site POST arrives without them while the
 * top-level redirect back from GitHub still carries the OAuth one.
 */
public final class Cookies {

  public static final String SESSION = "__Host-docswatcher_session";
  public static final String OAUTH = "__Host-docswatcher_oauth";

  private Cookies() {}

  public static String set(String name, String value, Duration maxAge) {
    return ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(maxAge).build().toString();
  }

  public static String clear(String name) {
    return set(name, "", Duration.ZERO);
  }

  public static String read(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie c : cookies) {
      if (name.equals(c.getName())) {
        return c.getValue();
      }
    }
    return null;
  }

  /**
   * Whether a state-changing request came from the site itself. SameSite=Lax already keeps the
   * session cookie off cross-site POSTs; this is the second lock, and the one that does not depend
   * on the browser's cookie policy. Fails closed: no Origin and no Sec-Fetch-Site is refused.
   */
  public static boolean sameOrigin(HttpServletRequest request, String webOrigin) {
    String origin = request.getHeader("Origin");
    if (origin != null) {
      return origin.equals(stripSlash(webOrigin));
    }
    return "same-origin".equals(request.getHeader("Sec-Fetch-Site"));
  }

  static String stripSlash(String url) {
    return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
