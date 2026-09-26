package dev.docswatcher.app.earlyaccess;

import dev.docswatcher.app.config.PublicForms;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

  private final EarlyAccessStore store;
  private final EarlyAccessNotifier notifier;
  private final PublicForms.RateLimit limit;

  @Autowired
  public EarlyAccessController(EarlyAccessStore store, EarlyAccessNotifier notifier) {
    this(store, notifier, Clock.systemUTC());
  }

  EarlyAccessController(EarlyAccessStore store, EarlyAccessNotifier notifier, Clock clock) {
    this.store = store;
    this.notifier = notifier;
    this.limit = new PublicForms.RateLimit(PER_WINDOW, WINDOW, clock);
  }

  @PostMapping("/early-access")
  public ResponseEntity<Map<String, Object>> submit(@RequestBody Form form, HttpServletRequest request) {
    if (!limit.allow(PublicForms.client(request))) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("ok", false, "error", "Too many requests. Try again later."));
    }
    // A bot filled the field people never see. Say yes and store nothing, so it learns nothing.
    if (form.website() != null && !form.website().isBlank()) return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));

    String email = PublicForms.clean(form.email(), 254).toLowerCase(Locale.ROOT);
    if (!PublicForms.isEmail(email)) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Please enter a valid email address."));
    }
    String company = PublicForms.clean(form.company(), MAX_FIELD);
    String repositories = PublicForms.clean(form.repositories(), MAX_FIELD);
    String providers = PublicForms.clean(form.providers(), MAX_FIELD);
    String interest = PublicForms.clean(form.interest(), MAX_FIELD);
    String message = PublicForms.cleanText(form.message(), MAX_MESSAGE);
    int requests = store.save(email, company, repositories, providers, interest, message);
    notifier.tell(new EarlyAccessNotifier.Lead(email, company, repositories, providers, interest, message, requests));
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
  }

  /** For the owner. Behind the API token, like everything under /api. */
  @GetMapping("/api/early-access")
  public List<EarlyAccessStore.Request> list() {
    return store.all();
  }
}
