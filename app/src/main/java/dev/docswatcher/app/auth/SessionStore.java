package dev.docswatcher.app.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Signed-in sessions, in Postgres.
 *
 * <p>The browser holds a random 256-bit value and nothing else. The table holds only its SHA-256,
 * so a copy of the database is not a set of live sessions. A row is the whole session: who, and
 * which repositories GitHub said they could see. Signing out deletes the row, so it ends at once,
 * which a signed stateless token could not do without a revocation list (ADR 0008).
 */
@Repository
public class SessionStore {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final TypeReference<List<UserSession.OrgAccess>> ORGS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final Clock clock;

  @Autowired
  public SessionStore(JdbcClient jdbc, ObjectMapper mapper) {
    this(jdbc, mapper, Clock.systemUTC());
  }

  SessionStore(JdbcClient jdbc, ObjectMapper mapper, Clock clock) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Stores a session and returns the value for the cookie. The value itself is never stored. */
  public String create(long githubId, String login, String name, String avatarUrl, List<UserSession.OrgAccess> orgs, Duration ttl) {
    return create(githubId, login, name, avatarUrl, orgs, ttl, "github");
  }

  /** As above, for a person who signed in with {@code provider}: "github" or "gitlab". */
  public String create(long userId, String login, String name, String avatarUrl, List<UserSession.OrgAccess> orgs, Duration ttl, String provider) {
    String token = randomToken();
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    jdbc.sql(
            """
            insert into user_session (token_hash, github_id, login, name, avatar_url, access, created_at, expires_at, provider)
            values (:hash, :id, :login, :name, :avatar, cast(:access as jsonb), :now, :expires, :provider)
            """)
        .param("hash", hash(token))
        .param("id", userId)
        .param("provider", provider)
        .param("login", login)
        .param("name", name)
        .param("avatar", avatarUrl)
        .param("access", mapper.writeValueAsString(orgs))
        .param("now", now)
        .param("expires", now.plus(ttl))
        .update();
    return token;
  }

  /** The live session for a cookie value. Expired or unknown values are simply absent. */
  public Optional<UserSession> find(String token) {
    if (token == null || token.isBlank() || token.length() > 128) {
      return Optional.empty();
    }
    return jdbc.sql(
            """
            select github_id, login, name, avatar_url, access::text as access, created_at, expires_at, provider
            from user_session where token_hash = :hash and expires_at > :now
            """)
        .param("hash", hash(token))
        .param("now", OffsetDateTime.now(clock))
        .query((rs, n) -> new UserSession(
            rs.getLong("github_id"),
            rs.getString("login"),
            rs.getString("name"),
            rs.getString("avatar_url"),
            mapper.readValue(rs.getString("access"), ORGS),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getString("provider")))
        .optional();
  }

  public void delete(String token) {
    if (token != null && !token.isBlank()) {
      jdbc.sql("delete from user_session where token_hash = :hash").param("hash", hash(token)).update();
    }
  }

  /** Housekeeping, run on each sign-in: expired rows are dead weight, never a risk. */
  public int purgeExpired() {
    return jdbc.sql("delete from user_session where expires_at <= :now").param("now", OffsetDateTime.now(clock)).update();
  }

  /** 256 random bits, URL-safe. Used for session values, OAuth state and PKCE verifiers. */
  public static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
