package dev.docwatcher.app.store;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContractStore {

  private final JdbcClient jdbc;

  public ContractStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void upsert(StoredContract c) {
    jdbc.sql(
            """
            insert into contract (repo_id, id, provider, kind, key, confidence, evidence, context, first_seen_sha, last_seen_sha)
            values (:repo, :id, :provider, :kind, :key, :confidence, cast(:evidence as jsonb), cast(:context as jsonb), :sha, :sha)
            on conflict (repo_id, id) do update set
              provider = excluded.provider, kind = excluded.kind, key = excluded.key, confidence = excluded.confidence,
              evidence = excluded.evidence, context = excluded.context, last_seen_sha = excluded.last_seen_sha
            """)
        .param("repo", c.repoId())
        .param("id", c.id())
        .param("provider", c.provider())
        .param("kind", c.kind())
        .param("key", c.key())
        .param("confidence", c.confidence())
        .param("evidence", c.evidenceJson())
        .param("context", c.contextJson())
        .param("sha", c.lastSeenSha())
        .update();
  }

  public List<StoredContract> forRepo(long repoId) {
    return jdbc.sql("select repo_id, id, provider, kind, key, confidence, evidence::text as evidence_json, context::text as context_json, first_seen_sha, last_seen_sha from contract where repo_id = :r order by id")
        .param("r", repoId)
        .query(StoredContract.class)
        .list();
  }

  /** Contracts observed in the most recent scan of the repo. */
  public List<StoredContract> currentForRepo(long repoId) {
    return jdbc.sql(
            """
            select c.repo_id, c.id, c.provider, c.kind, c.key, c.confidence, c.evidence::text as evidence_json,
                   c.context::text as context_json, c.first_seen_sha, c.last_seen_sha
            from contract c join repo r on r.id = c.repo_id
            where c.repo_id = :r and c.last_seen_sha = r.last_scanned_sha order by c.id
            """)
        .param("r", repoId)
        .query(StoredContract.class)
        .list();
  }

  public record ProviderCount(String provider, long contracts, long evidence) {}

  public List<ProviderCount> countByProvider(String login) {
    return jdbc.sql(
            """
            select c.provider, count(*) as contracts, coalesce(sum(jsonb_array_length(c.evidence)), 0) as evidence
            from contract c join repo r on r.id = c.repo_id join installation i on i.id = r.installation_id
            where i.account_login = :login and c.last_seen_sha = r.last_scanned_sha
            group by c.provider order by c.provider
            """)
        .param("login", login)
        .query(ProviderCount.class)
        .list();
  }

  public long countForLogin(String login) {
    return jdbc.sql(
            """
            select count(*) from contract c join repo r on r.id = c.repo_id join installation i on i.id = r.installation_id
            where i.account_login = :login and c.last_seen_sha = r.last_scanned_sha
            """)
        .param("login", login)
        .query(Long.class)
        .single();
  }
}
