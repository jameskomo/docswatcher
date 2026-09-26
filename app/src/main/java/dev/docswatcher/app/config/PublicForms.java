package dev.docswatcher.app.config;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What every public, unauthenticated form endpoint shares: who the client is, how its fields are
 * cleaned, what an email address looks like, and a rate limit per client. The early-access form
 * (ADR 0005) and the email alert subscription (ADR 0010) both use it, so they cannot drift apart.
 */
public final class PublicForms {

  /** Deliberately loose: one @, no spaces, a dot in the domain. The confirmation email is the real test. */
  public static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,188}\\.[^\\s@]{2,63}$");

  private PublicForms() {}

  public static boolean isEmail(String value) {
    return value != null && value.length() <= 254 && EMAIL.matcher(value).matches();
  }

  /** Cloudflare sets CF-Connecting-IP to the real client; behind the tunnel the socket address is not. */
  public static String client(HttpServletRequest request) {
    String cf = request.getHeader("CF-Connecting-IP");
    if (cf != null && !cf.isBlank()) return cf.trim();
    return request.getRemoteAddr();
  }

  /** One line: trimmed, control characters replaced by spaces, and cut to a length. */
  public static String clean(String value, int max) {
    if (value == null) return "";
    return cut(value.replaceAll("\\p{Cntrl}", " ").strip(), max);
  }

  /** Free text: as {@link #clean}, but the line breaks someone typed are kept. */
  public static String cleanText(String value, int max) {
    if (value == null) return "";
    return cut(value.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip(), max);
  }

  private static String cut(String s, int max) {
    return s.length() > max ? s.substring(0, max) : s;
  }

  /** A sliding-window limit per client address, held in memory. */
  public static final class RateLimit {

    private final int perWindow;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

    public RateLimit(int perWindow, Duration window, Clock clock) {
      this.perWindow = perWindow;
      this.window = window;
      this.clock = clock;
    }

    /** Counts this request and says whether it is within the limit. */
    public boolean allow(String client) {
      Instant now = clock.instant();
      Deque<Instant> times = recent.computeIfAbsent(client, k -> new ArrayDeque<>());
      synchronized (times) {
        while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(window))) times.pollFirst();
        if (times.size() >= perWindow) return false;
        times.addLast(now);
      }
      // Keep the map from growing without bound on a long-running server: forget clients whose
      // newest request has left the window.
      if (recent.size() > 10_000) {
        Instant cutoff = now.minus(window);
        recent.entrySet().removeIf(e -> {
          synchronized (e.getValue()) {
            Instant last = e.getValue().peekLast();
            return last == null || last.isBefore(cutoff);
          }
        });
      }
      return true;
    }
  }
}
