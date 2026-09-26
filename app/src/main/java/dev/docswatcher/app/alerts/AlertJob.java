package dev.docswatcher.app.alerts;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a day: team alerts, then public subscriber alerts, then housekeeping. ADR 0010.
 *
 * <p>Every run looks at what is due as of today and what was already sent, so running it twice
 * sends nothing new, and a day it did not run is made up on the next.
 */
@Component
public class AlertJob {

  private static final Logger log = LoggerFactory.getLogger(AlertJob.class);

  /** What one run did. */
  public record Summary(LocalDate day, TeamAlerts.Result teams, SubscriberAlerts.Result subscribers) {}

  private final TeamAlerts teams;
  private final SubscriberAlerts subscribers;
  private final AlertStore alertStore;
  private final SubscriberStore subscriberStore;
  private final Clock clock;
  private final ReentrantLock running = new ReentrantLock();

  @Autowired
  public AlertJob(TeamAlerts teams, SubscriberAlerts subscribers, AlertStore alertStore, SubscriberStore subscriberStore) {
    this(teams, subscribers, alertStore, subscriberStore, Clock.systemUTC());
  }

  AlertJob(TeamAlerts teams, SubscriberAlerts subscribers, AlertStore alertStore, SubscriberStore subscriberStore, Clock clock) {
    this.teams = teams;
    this.subscribers = subscribers;
    this.alertStore = alertStore;
    this.subscriberStore = subscriberStore;
    this.clock = clock;
  }

  @Scheduled(cron = "${docswatcher.alerts.cron:0 0 6 * * *}", zone = "UTC")
  public void daily() {
    try {
      run(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    } catch (RuntimeException e) {
      log.error("The daily alert run failed; the next run will catch up", e);
    }
  }

  /** One run as of {@code today}. Runs never overlap: two at once could both send before either records. */
  public Summary run(LocalDate today) {
    running.lock();
    try {
      TeamAlerts.Result t = teams.run(today);
      SubscriberAlerts.Result s = subscribers.run(today);
      alertStore.purge(today.minusDays(30));
      subscriberStore.purgeSent(today.minusDays(30));
      subscriberStore.purgeUnconfirmed(clock.instant().minus(java.time.Duration.ofDays(SubscriptionController.CONFIRM_DAYS)));
      return new Summary(today, t, s);
    } finally {
      running.unlock();
    }
  }
}
