package dev.docswatcher.app.alerts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Public email alert subscribers. Only the address, its provider filter, whether and when it was
 * confirmed, and what it has been sent. ADR 0010.
 */
@Repository
public class SubscriberStore {

  /** A subscriber. An empty provider list means every provider. */
  public record Subscriber(String email, List<String> providers, OffsetDateTime confirmedAt) {}

  private final JdbcClient jdbc;

  public SubscriberStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  static String csv(List<String> providers) {
    return String.join(",", providers);
  }

  static List<String> list(String csv) {
    return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
  }

  /**
   * Notes that a confirmation email is about to go to this address, and says whether it may: at
   * most one per address per {@code gap}, so the form cannot be used to flood someone's inbox. A
   * new address is stored unconfirmed; an existing subscription is left exactly as it is until the
   * new choice is confirmed.
   */
  public boolean mayConfirm(String email, List<String> providers, Instant now, java.time.Duration gap) {
    return jdbc.sql("""
            insert into subscriber (email, providers, confirm_sent_at) values (:email, :providers, :now)
            on conflict (email) do update set confirm_sent_at = :now
              where subscriber.confirm_sent_at is null or subscriber.confirm_sent_at < :cutoff
            returning email
            """)
        .param("email", email)
        .param("providers", csv(providers))
        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
        .param("cutoff", OffsetDateTime.ofInstant(now.minus(gap), ZoneOffset.UTC))
        .query(String.class)
        .optional()
        .isPresent();
  }

  /** The owner of the address clicked the link: subscribed, with the providers the link carried. */
  public void confirm(String email, List<String> providers) {
    jdbc.sql("""
            insert into subscriber (email, providers, confirmed_at) values (:email, :providers, now())
            on conflict (email) do update set providers = excluded.providers,
              confirmed_at = coalesce(subscriber.confirmed_at, now())
            """)
        .param("email", email)
        .param("providers", csv(providers))
        .update();
  }

  /** Forgets the address entirely, with what it was sent. Returns whether there was anything to forget. */
  public boolean delete(String email) {
    return jdbc.sql("delete from subscriber where email = :email").param("email", email).update() > 0;
  }

  public Optional<Subscriber> find(String email) {
    return jdbc.sql("select email, providers, confirmed_at from subscriber where email = :email")
        .param("email", email)
        .query((rs, n) -> new Subscriber(rs.getString("email"), list(rs.getString("providers")), rs.getObject("confirmed_at", OffsetDateTime.class)))
        .optional();
  }

  public List<Subscriber> confirmed() {
    return jdbc.sql("select email, providers, confirmed_at from subscriber where confirmed_at is not null order by email")
        .query((rs, n) -> new Subscriber(rs.getString("email"), list(rs.getString("providers")), rs.getObject("confirmed_at", OffsetDateTime.class)))
        .list();
  }

  static String key(String email, String changeId, LocalDate effective) {
    return email + "\n" + changeId + "\n" + effective;
  }

  /** What has been sent about dates not yet past, keyed by {@link #key}. */
  public Map<String, Set<Integer>> sent(LocalDate today) {
    Map<String, Set<Integer>> out = new HashMap<>();
    jdbc.sql("select email, change_id, effective, threshold from subscriber_sent where effective >= :today")
        .param("today", today)
        .query((RowCallbackHandler) rs -> out
            .computeIfAbsent(key(rs.getString("email"), rs.getString("change_id"), rs.getObject("effective", LocalDate.class)), k -> new HashSet<>())
            .add(rs.getInt("threshold")));
    return out;
  }

  public void record(String email, String changeId, LocalDate effective, List<Integer> thresholds) {
    for (int t : thresholds) {
      jdbc.sql("""
              insert into subscriber_sent (email, change_id, effective, threshold)
              values (:email, :change, :effective, :threshold) on conflict do nothing
              """)
          .param("email", email)
          .param("change", changeId)
          .param("effective", effective)
          .param("threshold", t)
          .update();
    }
  }

  /** Addresses never confirmed are forgotten once their link has expired. */
  public int purgeUnconfirmed(Instant before) {
    return jdbc.sql("delete from subscriber where confirmed_at is null and created_at < :before")
        .param("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
        .update();
  }

  public int purgeSent(LocalDate before) {
    return jdbc.sql("delete from subscriber_sent where effective < :before").param("before", before).update();
  }
}
