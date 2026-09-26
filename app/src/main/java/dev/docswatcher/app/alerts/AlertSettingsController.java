package dev.docswatcher.app.alerts;

import dev.docswatcher.app.auth.MemberAccess;
import dev.docswatcher.app.auth.Viewer;
import dev.docswatcher.app.config.PublicForms;
import dev.docswatcher.app.store.Installation;
import dev.docswatcher.app.store.InstallationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An organisation's alert settings, on the dashboard. ADR 0010.
 *
 * <p>Anyone who can see the organisation can read them; changing them, or sending a test, needs
 * write access to one of its repositories ({@link Viewer#canWriteOrg}), checked by
 * {@code AccessInterceptor} before a handler runs, with the same-origin rule every member's
 * state change is held to. The Slack webhook is a credential, so it is never sent back: only
 * whether there is one, and its last four characters.
 */
@RestController
@RequestMapping("/api")
public class AlertSettingsController {

  static final int MAX_EMAILS = 10;
  static final int TESTS_PER_HOUR = 3;

  /** What the dashboard sends. {@code slackWebhook}: null keeps the current one, blank removes it. */
  public record Update(Boolean enabled, List<String> emails, String slackWebhook, List<Integer> thresholds) {}

  public record Slack(boolean configured, String hint) {}

  public record View(String login, boolean enabled, List<String> emails, Slack slack, List<Integer> thresholds,
      boolean canEdit, boolean emailAvailable, String updatedBy, OffsetDateTime updatedAt) {}

  private final AlertStore store;
  private final InstallationStore installations;
  private final TeamAlerts alerts;
  private final BrevoMailer mailer;
  private final AlertJob job;
  private final Clock clock;
  private final PublicForms.RateLimit tests;

  @Autowired
  public AlertSettingsController(AlertStore store, InstallationStore installations, TeamAlerts alerts, BrevoMailer mailer, AlertJob job) {
    this(store, installations, alerts, mailer, job, Clock.systemUTC());
  }

  AlertSettingsController(AlertStore store, InstallationStore installations, TeamAlerts alerts, BrevoMailer mailer, AlertJob job,
      Clock clock) {
    this.store = store;
    this.installations = installations;
    this.alerts = alerts;
    this.mailer = mailer;
    this.job = job;
    this.clock = clock;
    this.tests = new PublicForms.RateLimit(TESTS_PER_HOUR, Duration.ofHours(1), clock);
  }

  @GetMapping("/orgs/{login}/alerts")
  @MemberAccess
  public ResponseEntity<View> get(@PathVariable("login") String login, Viewer viewer) {
    return installation(login)
        .map(i -> ResponseEntity.ok(view(i, store.find(i.id()).orElse(AlertStore.Settings.defaults(i.id())), viewer)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/orgs/{login}/alerts")
  @MemberAccess(MemberAccess.Level.WRITE)
  public ResponseEntity<?> save(@PathVariable("login") String login, @RequestBody Update update, Viewer viewer) {
    Optional<Installation> inst = installation(login);
    if (inst.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    AlertStore.Settings current = store.find(inst.get().id()).orElse(AlertStore.Settings.defaults(inst.get().id()));
    try {
      List<String> emails = emails(update.emails() == null ? current.emails() : update.emails());
      String slack = update.slackWebhook() == null ? current.slackWebhook() : slack(update.slackWebhook());
      List<Integer> thresholds = update.thresholds() == null ? current.thresholds() : Thresholds.normalise(update.thresholds());
      boolean enabled = update.enabled() == null ? current.enabled() : update.enabled();
      String by = viewer instanceof Viewer.Member m ? m.session().login() : "owner";
      store.save(new AlertStore.Settings(inst.get().id(), enabled, emails, slack, thresholds, by, null));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
    return ResponseEntity.ok(view(inst.get(), store.find(inst.get().id()).orElseThrow(), viewer));
  }

  /** Sends a test on every channel the organisation has. A few an hour, so it cannot be used to flood anyone. */
  @PostMapping("/orgs/{login}/alerts/test")
  @MemberAccess(MemberAccess.Level.WRITE)
  public ResponseEntity<?> test(@PathVariable("login") String login) {
    Optional<Installation> inst = installation(login);
    if (inst.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    Optional<AlertStore.Settings> settings = store.find(inst.get().id());
    if (settings.isEmpty() || (settings.get().emails().isEmpty() && settings.get().slackWebhook() == null)) {
      return ResponseEntity.badRequest().body(Map.of("error", "Add an email address or a Slack webhook first."));
    }
    if (!tests.allow(Long.toString(inst.get().id()))) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Too many tests. Try again in an hour."));
    }
    TeamAlerts.Result r = alerts.test(inst.get().accountLogin(), settings.get());
    return ResponseEntity.ok(r);
  }

  /** For the owner and their automation: runs the daily job now. Idempotent, like the job itself. */
  @PostMapping("/alerts/run")
  public AlertJob.Summary run() {
    return job.run(LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)));
  }

  /** The installation behind a login: the live one if there are several, newest first. */
  private Optional<Installation> installation(String login) {
    return installations.findByLogin(login).stream()
        .sorted(Comparator.comparing((Installation i) -> i.suspendedAt() != null)
            .thenComparing(Installation::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .findFirst();
  }

  private View view(Installation inst, AlertStore.Settings s, Viewer viewer) {
    String hook = s.slackWebhook();
    Slack slack = new Slack(hook != null, hook == null ? null : "…" + hook.substring(Math.max(0, hook.length() - 4)));
    return new View(inst.accountLogin(), s.enabled(), s.emails(), slack, s.thresholds(), viewer.canWriteOrg(inst.accountLogin()),
        mailer.configured(), s.updatedBy(), s.updatedAt());
  }

  static List<String> emails(List<String> requested) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String e : requested) {
      String email = PublicForms.clean(e, 254).toLowerCase(Locale.ROOT);
      if (email.isEmpty()) {
        continue;
      }
      if (!PublicForms.isEmail(email)) {
        throw new IllegalArgumentException("Not an email address: " + email);
      }
      out.add(email);
    }
    if (out.size() > MAX_EMAILS) {
      throw new IllegalArgumentException("At most " + MAX_EMAILS + " email addresses.");
    }
    return new ArrayList<>(out);
  }

  static String slack(String requested) {
    String url = requested.strip();
    if (url.isEmpty()) {
      return null;
    }
    if (!SlackNotifier.validWebhook(url)) {
      throw new IllegalArgumentException("A Slack incoming webhook starts with https://hooks.slack.com/services/.");
    }
    return url;
  }
}
