package dev.docswatcher.app.earlyaccess;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Teams page's early-access form. Public by design, so narrow by design: validated and
 * length-limited fields, a honeypot, and a rate limit per client. docs/adr/0005-early-access-requests.md.
 */
@RestController
public class EarlyAccessController {

  /** What the form sends. {@code website} is the honeypot: hidden from people, filled in by bots. */
  public record Form(String email, String company, String repositories, String providers, String interest,
      String message, String website) {}

  static final int MAX_FIELD = 200;
  static final int MAX_MESSAGE = 2000;
  static final int PER_WINDOW = 5;
  static final Duration WINDOW = Duration.ofHours(1);
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,188}\\.[^\\s@]{2,63}$");

  private final EarlyAccessStore store;
  private final Clock clock;
  private final Map<String, Deque<Instant>> recent = new ConcurrentHashMap<>();

  @Autowired
  public EarlyAccessController(EarlyAccessStore store) {
    this(store, Clock.systemUTC());
  }

  EarlyAccessController(EarlyAccessStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @PostMapping("/early-access")
  public ResponseEntity<Map<String, Object>> submit(@RequestBody Form form, HttpServletRequest request) {
    if (!allowed(client(request))) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("ok", false, "error", "Too many requests. Try again later."));
    }
    // A bot filled the field people never see. Say yes and store nothing, so it learns nothing.
    if (form.website() != null && !form.website().isBlank()) return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));

    String email = clean(form.email(), 254).toLowerCase(Locale.ROOT);
    if (!EMAIL.matcher(email).matches()) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Please enter a valid email address."));
    }
    store.save(email, clean(form.company(), MAX_FIELD), clean(form.repositories(), MAX_FIELD),
        clean(form.providers(), MAX_FIELD), clean(form.interest(), MAX_FIELD), cleanText(form.message(), MAX_MESSAGE));
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
  }

  /** For the owner. Behind the API token, like everything under /api. */
  @GetMapping("/api/early-access")
  public List<EarlyAccessStore.Request> list() {
    return store.all();
  }

  /** Cloudflare sets CF-Connecting-IP to the real client; behind the tunnel the socket address is not. */
  static String client(HttpServletRequest request) {
    String cf = request.getHeader("CF-Connecting-IP");
    if (cf != null && !cf.isBlank()) return cf.trim();
    return request.getRemoteAddr();
  }

  boolean allowed(String client) {
    Instant now = clock.instant();
    Deque<Instant> times = recent.computeIfAbsent(client, k -> new ArrayDeque<>());
    synchronized (times) {
      while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(WINDOW))) times.pollFirst();
      if (times.size() >= PER_WINDOW) return false;
      times.addLast(now);
    }
    // Keep the map from growing without bound on a long-running server: forget clients whose
    // newest request has left the window.
    if (recent.size() > 10_000) {
      Instant cutoff = now.minus(WINDOW);
      recent.entrySet().removeIf(e -> {
        synchronized (e.getValue()) {
          Instant last = e.getValue().peekLast();
          return last == null || last.isBefore(cutoff);
        }
      });
    }
    return true;
  }

  /** One line: trimmed, control characters replaced by spaces, and cut to a length. */
  static String clean(String value, int max) {
    if (value == null) return "";
    return cut(value.replaceAll("\\p{Cntrl}", " ").strip(), max);
  }

  /** Free text: as {@link #clean}, but the line breaks someone typed are kept. */
  static String cleanText(String value, int max) {
    if (value == null) return "";
    return cut(value.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip(), max);
  }

  private static String cut(String s, int max) {
    return s.length() > max ? s.substring(0, max) : s;
  }
}
