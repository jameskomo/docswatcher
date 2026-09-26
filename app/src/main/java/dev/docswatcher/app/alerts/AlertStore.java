package dev.docswatcher.app.alerts;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Team alert settings, the open findings they apply to, and what has been sent. ADR 0010. */
@Repository
public class AlertStore {

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
  private static final TypeReference<List<Integer>> INTEGERS = new TypeReference<>() {};

  /** One installation's settings. {@code slackWebhook} is null when there is none. */
  public record Settings(long installationId, boolean enabled, List<String> emails, String slackWebhook,
      List<Integer> thresholds, String updatedBy, OffsetDateTime updatedAt) {

    static Settings defaults(long installationId) {
      return new Settings(installationId, true, List.of(), null, Thresholds.DEFAULT, null, null);
    }
  }

  /**
   * An open finding with a date, with what an alert needs to say where it is: the repository, the
   * commit it was last scanned at, and the contract's evidence as stored.
   */
  public record Candidate(long installationId, String login, long repoId, String repoFullName, String ref,
      String contractId, String changeId, String severity, LocalDate effective, String evidenceJson) {

    String key(String channel) {
      return repoId + "\n" + contractId + "\n" + changeId + "\n" + effective + "\n" + channel;
    }
  }

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  public AlertStore(JdbcClient jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Optional<Settings> find(long installationId) {
    return jdbc.sql("""
            select installation_id, enabled, emails::text as emails, slack_webhook, thresholds::text as thresholds,
                   updated_by, updated_at
            from alert_settings where installation_id = :id
            """)
        .param("id", installationId)
        .query((rs, n) -> settings(rs))
        .optional();
  }

  /** The settings of every installation with alerts on and not suspended. */
  public List<Settings> enabled() {
    return jdbc.sql("""
            select s.installation_id, s.enabled, s.emails::text as emails, s.slack_webhook, s.thresholds::text as thresholds,
                   s.updated_by, s.updated_at
            from alert_settings s join installation i on i.id = s.installation_id
            where s.enabled and i.suspended_at is null
            """)
        .query((rs, n) -> settings(rs))
        .list();
  }

  private Settings settings(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Settings(
        rs.getLong("installation_id"),
        rs.getBoolean("enabled"),
        mapper.readValue(rs.getString("emails"), STRINGS),
        rs.getString("slack_webhook"),
        mapper.readValue(rs.getString("thresholds"), INTEGERS),
        rs.getString("updated_by"),
        rs.getObject("updated_at", OffsetDateTime.class));
  }

  public void save(Settings s) {
    jdbc.sql("""
            insert into alert_settings (installation_id, enabled, emails, slack_webhook, thresholds, updated_by, updated_at)
            values (:id, :enabled, cast(:emails as jsonb), :slack, cast(:thresholds as jsonb), :by, now())
            on conflict (installation_id) do update set
              enabled = excluded.enabled, emails = excluded.emails, slack_webhook = excluded.slack_webhook,
              thresholds = excluded.thresholds, updated_by = excluded.updated_by, updated_at = now()
            """)
        .param("id", s.installationId())
        .param("enabled", s.enabled())
        .param("emails", mapper.writeValueAsString(s.emails()))
        .param("slack", s.slackWebhook())
        .param("thresholds", mapper.writeValueAsString(s.thresholds()))
        .param("by", s.updatedBy())
        .update();
  }

  /** Takes one address off an installation's list: its recipient's unsubscribe link. Returns whether it was there. */
  @Transactional
  public boolean removeEmail(long installationId, String email) {
    Optional<Settings> s = jdbc.sql("""
            select installation_id, enabled, emails::text as emails, slack_webhook, thresholds::text as thresholds,
                   updated_by, updated_at
            from alert_settings where installation_id = :id for update
            """)
        .param("id", installationId)
        .query((rs, n) -> settings(rs))
        .optional();
    if (s.isEmpty() || !s.get().emails().contains(email)) {
      return false;
    }
    List<String> left = new ArrayList<>(s.get().emails());
    left.remove(email);
    jdbc.sql("update alert_settings set emails = cast(:emails as jsonb), updated_by = :by, updated_at = now() where installation_id = :id")
        .param("emails", mapper.writeValueAsString(left))
        .param("by", "unsubscribe link")
        .param("id", installationId)
        .update();
    return true;
  }

  /**
   * Findings an alert could be about: open, or snoozed with the snooze over, dated between today and
   * {@code until}, in an installation that is not suspended. Snoozed, not-in-production,
   * not-affected and fixed findings are never alerted about.
   */
  public List<Candidate> candidates(LocalDate today, LocalDate until) {
    return jdbc.sql("""
            select i.id as installation_id, i.account_login, r.id as repo_id, r.full_name,
                   coalesce(r.last_scanned_sha, r.default_branch) as ref,
                   f.contract_id, f.change_id, f.severity, f.effective, c.evidence::text as evidence
            from finding f
            join repo r on r.id = f.repo_id
            join installation i on i.id = r.installation_id
            left join contract c on c.repo_id = f.repo_id and c.id = f.contract_id
            where i.suspended_at is null
              and f.effective between :today and :until
              and (f.status = 'open' or (f.status = 'snoozed' and f.snoozed_until < :today))
            order by f.effective, r.full_name, f.contract_id
            """)
        .param("today", today)
        .param("until", until)
        .query((rs, n) -> new Candidate(
            rs.getLong("installation_id"), rs.getString("account_login"), rs.getLong("repo_id"), rs.getString("full_name"),
            rs.getString("ref"), rs.getString("contract_id"), rs.getString("change_id"), rs.getString("severity"),
            rs.getObject("effective", LocalDate.class), rs.getString("evidence")))
        .list();
  }

  /** What has been sent for dates not yet past, keyed by {@link Candidate#key(String)}. */
  public Map<String, Set<Integer>> sent(LocalDate today) {
    Map<String, Set<Integer>> out = new HashMap<>();
    jdbc.sql("select repo_id, contract_id, change_id, effective, threshold, channel from alert_sent where effective >= :today")
        .param("today", today)
        .query((RowCallbackHandler) rs -> {
          String key = rs.getLong("repo_id") + "\n" + rs.getString("contract_id") + "\n" + rs.getString("change_id") + "\n"
              + rs.getObject("effective", LocalDate.class) + "\n" + rs.getString("channel");
          out.computeIfAbsent(key, k -> new HashSet<>()).add(rs.getInt("threshold"));
        });
    return out;
  }

  public void record(Candidate c, List<Integer> thresholds, String channel) {
    for (int t : thresholds) {
      jdbc.sql("""
              insert into alert_sent (repo_id, contract_id, change_id, effective, threshold, channel)
              values (:repo, :contract, :change, :effective, :threshold, :channel) on conflict do nothing
              """)
          .param("repo", c.repoId())
          .param("contract", c.contractId())
          .param("change", c.changeId())
          .param("effective", c.effective())
          .param("threshold", t)
          .param("channel", channel)
          .update();
    }
  }

  /** Rows for dates long past are history nobody reads. */
  public int purge(LocalDate before) {
    return jdbc.sql("delete from alert_sent where effective < :before").param("before", before).update();
  }
}
