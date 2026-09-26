package dev.docswatcher.app.store;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScanRunStore {

  private static final String COLUMNS =
      "id, repo_id, sha, trigger, status, engine_version, knowledge_version, stats::text as stats_json, started_at, finished_at, error, created_at";

  private final JdbcClient jdbc;

  public ScanRunStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public long enqueue(long repoId, String sha, String trigger) {
    return jdbc.sql("insert into scan_run (repo_id, sha, trigger) values (:r, :sha, :t) returning id")
        .param("r", repoId).param("sha", sha).param("t", trigger).query(Long.class).single();
  }

  /** Claims one queued run inside a short transaction so several workers never take the same row. */
  @Transactional
  public Optional<ScanRun> claimNext() {
    Optional<ScanRun> next =
        jdbc.sql("select " + COLUMNS + " from scan_run where status = 'queued' order by created_at limit 1 for update skip locked")
            .query(ScanRun.class)
            .optional();
    next.ifPresent(run -> jdbc.sql("update scan_run set status = 'running', started_at = now() where id = :id").param("id", run.id()).update());
    return next.map(run -> find(run.id()).orElseThrow());
  }

  public void finish(long id, String engineVersion, String knowledgeVersion, String statsJson) {
    jdbc.sql(
            """
            update scan_run set status = 'done', finished_at = now(), engine_version = :e, knowledge_version = :k,
              stats = cast(:s as jsonb) where id = :id
            """)
        .param("e", engineVersion).param("k", knowledgeVersion).param("s", statsJson).param("id", id).update();
  }

  public void fail(long id, String error) {
    jdbc.sql("update scan_run set status = 'failed', finished_at = now(), error = :e where id = :id")
        .param("e", error).param("id", id).update();
  }

  public Optional<ScanRun> find(long id) {
    return jdbc.sql("select " + COLUMNS + " from scan_run where id = :id").param("id", id).query(ScanRun.class).optional();
  }

  public List<ScanRun> forRepo(long repoId) {
    return jdbc.sql("select " + COLUMNS + " from scan_run where repo_id = :r order by created_at desc").param("r", repoId).query(ScanRun.class).list();
  }

  public Optional<ScanRun> lastDone(long repoId) {
    return jdbc.sql("select " + COLUMNS + " from scan_run where repo_id = :r and status = 'done' order by finished_at desc limit 1")
        .param("r", repoId).query(ScanRun.class).optional();
  }

  public int cancelQueued(long repoId) {
    return jdbc.sql("update scan_run set status = 'failed', error = 'cancelled', finished_at = now() where repo_id = :r and status = 'queued'")
        .param("r", repoId).update();
  }

  /** How the error of a run the worker gave up on begins; also how its one retry is recognised. */
  public static final String ABANDONED = "abandoned: ";

  /** What {@link #reclaimAbandoned} did. */
  public record Reclaimed(int failed, int requeued) {}

  /**
   * Fails every run left {@code running} for longer than {@code olderThan}: whatever was running it
   * stopped (a restart, a deploy, a crash), and nothing else would ever finish it. Each is queued
   * again once, unless it was itself such a retry or its repository has a run queued already.
   *
   * <p>A retry is recognised without a column of its own: it is created in the transaction that
   * failed the original, so its {@code created_at} equals the original's {@code finished_at}.
   */
  @Transactional
  public Reclaimed reclaimAbandoned(Duration olderThan) {
    List<ScanRun> stale = jdbc.sql("select " + COLUMNS + " from scan_run where status = 'running'"
            + " and started_at < now() - make_interval(secs => :s) for update skip locked")
        .param("s", olderThan.toSeconds()).query(ScanRun.class).list();
    int requeued = 0;
    for (ScanRun run : stale) {
      boolean retry = jdbc.sql("""
              select exists (select 1 from scan_run p where p.repo_id = :r and p.status = 'failed' and p.error like :abandoned
                and p.finished_at = (select created_at from scan_run where id = :id))
              """)
          .param("r", run.repoId()).param("abandoned", ABANDONED + "%").param("id", run.id())
          .query(Boolean.class).single();
      boolean queued = jdbc.sql("select exists (select 1 from scan_run where repo_id = :r and status = 'queued')")
          .param("r", run.repoId()).query(Boolean.class).single();
      boolean again = !retry && !queued;
      String why = ABANDONED + "the scan was still running after " + olderThan.toMinutes()
          + " minutes, so whatever ran it had stopped"
          + (again ? "; it was queued again"
              : retry ? "; it was already a retry, so it was not queued again"
              : "; another scan of this repository was already queued");
      jdbc.sql("update scan_run set status = 'failed', finished_at = now(), error = :e where id = :id")
          .param("e", why).param("id", run.id()).update();
      if (again) {
        jdbc.sql("insert into scan_run (repo_id, sha, trigger, created_at) values (:r, :sha, :t, now())")
            .param("r", run.repoId()).param("sha", run.sha()).param("t", run.trigger()).update();
        requeued++;
      }
    }
    return new Reclaimed(stale.size(), requeued);
  }

  public boolean recordDelivery(String deliveryId) {
    return jdbc.sql("insert into webhook_delivery (id) values (:id) on conflict do nothing").param("id", deliveryId).update() == 1;
  }
}
