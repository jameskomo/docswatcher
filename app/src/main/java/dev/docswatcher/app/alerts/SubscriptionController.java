package dev.docswatcher.app.alerts;

import dev.docswatcher.app.config.PublicForms;
import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.model.ProviderDoc;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Email alerts for anyone, from the calendar page. ADR 0010.
 *
 * <p>{@code POST /subscribe} is public, so it is guarded like the early-access form: validated
 * fields, a honeypot, a rate limit per client. Nothing is sent to an address until its owner
 * confirms it, from a signed link in the one email the form can cause, and that email goes to a
 * given address at most once an hour. The answer is the same whatever the address's state, so the
 * form tells nobody who is subscribed.
 *
 * <p>The confirmation link opens a page with a button rather than confirming on the GET itself:
 * mail scanners fetch links, and a subscription must be a person's act. Unsubscribing is one click
 * ({@code GET /unsubscribe}), and mail programs can do it without a click through RFC 8058's
 * {@code POST /unsubscribe}.
 */
@RestController
public class SubscriptionController {

  private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

  /** What the form sends. {@code website} is the honeypot. {@code providers} empty means all. */
  public record Form(String email, List<String> providers, String website) {}

  static final int PER_WINDOW = 5;
  static final Duration WINDOW = Duration.ofHours(1);
  static final Duration CONFIRM_GAP = Duration.ofHours(1);
  static final int CONFIRM_DAYS = 7;
  static final int MAX_PROVIDERS = 50;

  private final SubscriberStore store;
  private final BrevoMailer mailer;
  private final AlertLinks links;
  private final LinkSigner signer;
  private final AlertStore teams;
  private final ScanEngine engine;
  private final Clock clock;
  private final PublicForms.RateLimit limit;

  @Autowired
  public SubscriptionController(SubscriberStore store, BrevoMailer mailer, AlertLinks links, LinkSigner signer, AlertStore teams,
      ScanEngine engine) {
    this(store, mailer, links, signer, teams, engine, Clock.systemUTC());
  }

  SubscriptionController(SubscriberStore store, BrevoMailer mailer, AlertLinks links, LinkSigner signer, AlertStore teams,
      ScanEngine engine, Clock clock) {
    this.store = store;
    this.mailer = mailer;
    this.links = links;
    this.signer = signer;
    this.teams = teams;
    this.engine = engine;
    this.clock = clock;
    this.limit = new PublicForms.RateLimit(PER_WINDOW, WINDOW, clock);
  }

  @PostMapping("/subscribe")
  public ResponseEntity<Map<String, Object>> subscribe(@RequestBody Form form, HttpServletRequest request) {
    if (!limit.allow(PublicForms.client(request))) {
      return error(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
    }
    if (form.website() != null && !form.website().isBlank()) {
      return accepted();
    }
    String email = PublicForms.clean(form.email(), 254).toLowerCase(Locale.ROOT);
    if (!PublicForms.isEmail(email)) {
      return error(HttpStatus.BAD_REQUEST, "Please enter a valid email address.");
    }
    Optional<List<String>> providers = providers(form.providers());
    if (providers.isEmpty()) {
      return error(HttpStatus.BAD_REQUEST, "Choose providers from the list.");
    }
    if (!mailer.configured()) {
      return error(HttpStatus.SERVICE_UNAVAILABLE, "Email alerts are not available right now. The calendar feed works meanwhile.");
    }
    if (store.mayConfirm(email, providers.get(), clock.instant(), CONFIRM_GAP)) {
      long expires = LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(CONFIRM_DAYS).toEpochDay();
      AlertText.Message m = AlertText.confirmation(links.confirm(email, SubscriberStore.csv(providers.get()), expires), names(providers.get()));
      if (!mailer.send(new BrevoMailer.Mail(email, m.subject(), m.text(), null))) {
        log.warn("A confirmation email was not sent");
      }
    }
    return accepted();
  }

  /** Known provider ids, sorted and without repeats. Empty for anything not in the knowledge base. */
  private Optional<List<String>> providers(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return Optional.of(List.of());
    }
    if (requested.size() > MAX_PROVIDERS) {
      return Optional.empty();
    }
    Set<String> known = engine.providers().stream().map(ProviderDoc::id).collect(Collectors.toSet());
    TreeSet<String> out = new TreeSet<>();
    for (String p : requested) {
      if (p == null || !known.contains(p)) {
        return Optional.empty();
      }
      out.add(p);
    }
    return Optional.of(List.copyOf(out));
  }

  private List<String> names(List<String> ids) {
    Map<String, String> names = engine.providers().stream().collect(Collectors.toMap(ProviderDoc::id, ProviderDoc::name, (a, b) -> a));
    return ids.stream().map(id -> names.getOrDefault(id, id)).toList();
  }

  @GetMapping("/subscribe/confirm")
  public ResponseEntity<String> confirmPage(@RequestParam(name = "token", required = false) String token) {
    Optional<Confirmation> c = confirmation(token);
    if (c.isEmpty()) {
      return page(HttpStatus.BAD_REQUEST, "This link has expired",
          "<p>Confirmation links last " + CONFIRM_DAYS + " days. Subscribe again from the <a href=\"" + esc(links.calendar()) + "\">calendar</a>.</p>");
    }
    String which = c.get().providers().isEmpty() ? "every provider DocsWatcher tracks" : String.join(", ", names(c.get().providers()));
    return page(HttpStatus.OK, "Confirm your alerts",
        "<p>Email <strong>" + esc(c.get().email()) + "</strong> 30 and 7 days before an API shutdown for " + esc(which) + ".</p>"
            + "<form method=\"post\" action=\"confirm\"><input type=\"hidden\" name=\"token\" value=\"" + esc(token) + "\">"
            + "<button type=\"submit\">Confirm</button></form>");
  }

  @PostMapping("/subscribe/confirm")
  public ResponseEntity<String> confirm(@RequestParam(name = "token", required = false) String token) {
    Optional<Confirmation> c = confirmation(token);
    if (c.isEmpty()) {
      return page(HttpStatus.BAD_REQUEST, "This link has expired",
          "<p>Subscribe again from the <a href=\"" + esc(links.calendar()) + "\">calendar</a>.</p>");
    }
    store.confirm(c.get().email(), c.get().providers());
    return page(HttpStatus.OK, "You are subscribed",
        "<p>DocsWatcher will email <strong>" + esc(c.get().email()) + "</strong> 30 and 7 days before each shutdown. "
            + "Every email has a link to stop them.</p><p><a href=\"" + esc(links.calendar()) + "\">Back to the calendar</a></p>");
  }

  /** One click, from the link in every alert email. */
  @GetMapping("/unsubscribe")
  public ResponseEntity<String> unsubscribePage(@RequestParam(name = "token", required = false) String token) {
    if (!unsubscribe(token)) {
      return page(HttpStatus.BAD_REQUEST, "This link does not work",
          "<p>It may have been cut short by your mail program. Try copying the whole link.</p>");
    }
    return page(HttpStatus.OK, "You are unsubscribed", "<p>DocsWatcher will send no more alerts to this address.</p>");
  }

  /** RFC 8058 one-click unsubscribe, which mail programs send on the reader's behalf. */
  @PostMapping("/unsubscribe")
  public ResponseEntity<String> unsubscribe(HttpServletRequest request) {
    boolean done = unsubscribe(request.getParameter("token"));
    return ResponseEntity.status(done ? HttpStatus.OK : HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_PLAIN)
        .cacheControl(CacheControl.noStore()).body(done ? "Unsubscribed.\n" : "Not a valid link.\n");
  }

  private boolean unsubscribe(String token) {
    Optional<List<String>> fields = signer.verify(token);
    if (fields.isEmpty()) {
      return false;
    }
    List<String> f = fields.get();
    if (f.size() == 2 && AlertLinks.SUBSCRIBER.equals(f.get(0))) {
      store.delete(f.get(1));
      return true;
    }
    if (f.size() == 3 && AlertLinks.TEAM.equals(f.get(0))) {
      try {
        teams.removeEmail(Long.parseLong(f.get(1)), f.get(2));
        return true;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return false;
  }

  record Confirmation(String email, List<String> providers) {}

  private Optional<Confirmation> confirmation(String token) {
    Optional<List<String>> fields = signer.verify(token);
    if (fields.isEmpty() || fields.get().size() != 4 || !AlertLinks.CONFIRM.equals(fields.get().get(0))) {
      return Optional.empty();
    }
    List<String> f = fields.get();
    try {
      if (LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay() > Long.parseLong(f.get(3))) {
        return Optional.empty();
      }
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    return Optional.of(new Confirmation(f.get(1), new ArrayList<>(SubscriberStore.list(f.get(2)))));
  }

  private static ResponseEntity<Map<String, Object>> accepted() {
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("ok", true));
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("ok", false, "error", message));
  }

  private static String esc(String s) {
    return HtmlUtils.htmlEscape(s);
  }

  /**
   * A small page of the app's own, fixed markup with every value escaped. No script, nothing
   * loaded from anywhere, never framed, never cached, and no Referer, since the URL holds a token.
   */
  private static ResponseEntity<String> page(HttpStatus status, String title, String body) {
    String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"robots\" content=\"noindex\">"
        + "<title>" + esc(title) + ", DocsWatcher</title><style>"
        + "body{font:16px/1.5 system-ui,sans-serif;max-width:36rem;margin:3rem auto;padding:0 1rem;color:#1a1a1a;background:#fff}"
        + "@media (prefers-color-scheme:dark){body{color:#e8e8e8;background:#111}a{color:#8ab4ff}}"
        + "button{font:inherit;padding:.5rem 1.25rem;border-radius:6px;border:1px solid currentColor;background:none;color:inherit;cursor:pointer}"
        + "</style></head><body><h1>" + esc(title) + "</h1>" + body + "</body></html>";
    return ResponseEntity.status(status)
        .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
        .cacheControl(CacheControl.noStore())
        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        .header("Referrer-Policy", "no-referrer")
        .header("X-Content-Type-Options", "nosniff")
        .body(html);
  }
}
