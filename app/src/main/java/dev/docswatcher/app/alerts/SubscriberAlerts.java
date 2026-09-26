package dev.docswatcher.app.alerts;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.ProviderDoc;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Public email alerts: each confirmed subscriber is emailed about the tracked shutdowns, in the
 * providers they chose, that have just come within 30 or 7 days. One email per subscriber per run
 * at most, recorded as sent only once Brevo accepted it. ADR 0010.
 */
@Service
public class SubscriberAlerts {

  private static final Logger log = LoggerFactory.getLogger(SubscriberAlerts.class);

  public record Result(int emails, int failures) {}

  private final SubscriberStore store;
  private final BrevoMailer mailer;
  private final AlertLinks links;
  private final ScanEngine engine;

  public SubscriberAlerts(SubscriberStore store, BrevoMailer mailer, AlertLinks links, ScanEngine engine) {
    this.store = store;
    this.mailer = mailer;
    this.links = links;
    this.engine = engine;
  }

  public Result run(LocalDate today) {
    if (!mailer.configured()) {
      return new Result(0, 0);
    }
    int furthest = Thresholds.DEFAULT.stream().mapToInt(Integer::intValue).max().orElse(0);
    List<ChangeDoc> soon = engine.changes().stream()
        .filter(c -> "active".equals(c.status()) && c.effective() != null)
        .filter(c -> !c.effective().isBefore(today) && !c.effective().isAfter(today.plusDays(furthest)))
        .sorted(Comparator.comparing(ChangeDoc::effective).thenComparing(ChangeDoc::id))
        .toList();
    if (soon.isEmpty()) {
      return new Result(0, 0);
    }
    Map<String, String> names = engine.providers().stream().collect(Collectors.toMap(ProviderDoc::id, ProviderDoc::name, (a, b) -> a));
    Map<String, Set<Integer>> sent = store.sent(today);

    int emails = 0, failures = 0;
    for (SubscriberStore.Subscriber s : store.confirmed()) {
      List<ChangeDoc> due = soon.stream()
          .filter(c -> s.providers().isEmpty() || s.providers().contains(c.provider()))
          .filter(c -> Thresholds.due(days(c, today), Thresholds.DEFAULT,
              sent.getOrDefault(SubscriberStore.key(s.email(), c.id(), c.effective()), Set.of())).isPresent())
          .toList();
      if (due.isEmpty()) {
        continue;
      }
      List<AlertText.Item> items = due.stream().map(c -> new AlertText.Item(
          c.title(), names.getOrDefault(c.provider(), c.provider()), c.severity(), c.effective(), days(c, today), c.summary(),
          c.migration() == null || !AlertText.isWebLink(c.migration().guide()) ? null : c.migration().guide(),
          List.of())).toList();
      String unsubscribe = links.unsubscribeSubscriber(s.email());
      AlertText.Message m = AlertText.subscriberEmail(items, links.calendar(), unsubscribe);
      if (mailer.send(new BrevoMailer.Mail(s.email(), m.subject(), m.text(), unsubscribe))) {
        for (ChangeDoc c : due) {
          store.record(s.email(), c.id(), c.effective(), Thresholds.crossed(days(c, today), Thresholds.DEFAULT));
        }
        emails++;
      } else {
        failures++;
      }
    }
    if (emails + failures > 0) {
      log.info("Subscriber alerts: {} email(s), {} failed and left for the next run", emails, failures);
    }
    return new Result(emails, failures);
  }

  private static long days(ChangeDoc c, LocalDate today) {
    return ChronoUnit.DAYS.between(today, c.effective());
  }
}
