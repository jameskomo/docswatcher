package dev.docswatcher.app.store;

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

  public boolean recordDelivery(String deliveryId) {
    return jdbc.sql("insert into webhook_delivery (id) values (:id) on conflict do nothing").param("id", deliveryId).update() == 1;
  }
}
