package dev.docswatcher.app.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Ingest tokens: one repository's credential for posting telemetry.
 *
 * <p>The customer holds the token; the table holds its SHA-256 and the first few characters, so
 * a copy of the database is not a set of working credentials and a person can still tell their
 * tokens apart. A token authorises exactly one thing, posting spans for the repository it
 * belongs to, and nothing on the read side (docs/13-runtime-observation.md).
 */
@Repository
public class IngestTokenStore {

  /** Every ingest token starts with this, so a mistyped owner token is not looked up at all. */
  public static final String PREFIX = "dwi_";

  /** Enough for every service and environment a repository reports from, few enough to audit. */
  public static final int MAX_ACTIVE_PER_REPO = 20;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String COLUMNS = "id, repo_id, prefix, label, created_by, created_at, last_used_at";

  private final JdbcClient jdbc;

  public IngestTokenStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** What a person sees about a token. Never the token itself. */
  public record IngestToken(long id, long repoId, String prefix, String label, String createdBy, OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {}

  /** A token just created: the only moment the secret exists outside the customer's hands. */
  public record Created(IngestToken token, String secret) {}

  public Created create(long repoId, String label, String createdBy) {
    String secret = PREFIX + random();
    IngestToken token = jdbc.sql(
            "insert into runtime_ingest_token (repo_id, token_hash, prefix, label, created_by) "
                + "values (:repo, :hash, :prefix, :label, :by) returning " + COLUMNS)
        .param("repo", repoId)
        .param("hash", hash(secret))
        .param("prefix", secret.substring(0, PREFIX.length() + 6))
        .param("label", label)
        .param("by", createdBy)
        .query(IngestToken.class)
        .single();
    return new Created(token, secret);
  }

  public List<IngestToken> active(long repoId) {
    return jdbc.sql("select " + COLUMNS + " from runtime_ingest_token where repo_id = :r and revoked_at is null order by created_at, id")
        .param("r", repoId).query(IngestToken.class).list();
  }

  public int countActive(long repoId) {
    return jdbc.sql("select count(*) from runtime_ingest_token where repo_id = :r and revoked_at is null")
        .param("r", repoId).query(Integer.class).single();
  }

  /** Revokes one of this repository's tokens. False when there is no such live token here. */
  public boolean revoke(long repoId, long id) {
    return jdbc.sql("update runtime_ingest_token set revoked_at = now() where id = :id and repo_id = :r and revoked_at is null")
        .param("id", id).param("r", repoId).update() == 1;
  }

  /**
   * The live token this secret names, if any. Marks it used, at most once a minute, in the same
   * statement, so an export costs one round trip and a busy collector does not write a row per
   * batch.
   */
  public Optional<IngestToken> authenticate(String secret) {
    if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 128) {
      return Optional.empty();
    }
    return jdbc.sql(
            """
            with found as (
              select * from runtime_ingest_token where token_hash = :hash and revoked_at is null
            ), touched as (
              update runtime_ingest_token set last_used_at = now()
              where id in (select id from found)
                and (last_used_at is null or last_used_at < now() - interval '1 minute')
            )
            select id, repo_id, prefix, label, created_by, created_at, last_used_at from found
            """)
        .param("hash", hash(secret))
        .query(IngestToken.class)
        .optional();
  }

  /** When any of this repository's tokens last delivered an export, revoked ones included. */
  public Optional<OffsetDateTime> lastUsed(long repoId) {
    return jdbc.sql("select max(last_used_at) from runtime_ingest_token where repo_id = :r")
        .param("r", repoId).query(OffsetDateTime.class).optional();
  }

  private static String random() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static byte[] hash(String secret) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
