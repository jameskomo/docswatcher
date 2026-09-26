package dev.docswatcher.app.support;

import dev.docswatcher.app.gitlab.GitLabStore;
import dev.docswatcher.app.gitlab.TokenCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;

/** A GitLab connection put straight into the database, as a successful connect would leave it. */
public final class GitLabSeed {

  /** The connection's access token, as GitLab would have issued it. */
  public static final String ACCESS_TOKEN = "glpat-test-access-token";
  /** The access token's bot user: DocsWatcher's own actions arrive as this user. */
  public static final long BOT_USER = 7777;

  private GitLabSeed() {}

  public record Seeded(long id, String webhookToken) {}

  public static Seeded group(GitLabStore store, TokenCipher cipher, String path, long groupId) {
    long id = store.nextId();
    String webhookToken = "hook-" + id + "-" + path;
    String login = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
    store.create(id, login, "group", groupId, path, cipher.encrypt(ACCESS_TOKEN, id), BOT_USER, LocalDate.of(2027, 9, 1),
        sha256(webhookToken), "owner");
    return new Seeded(id, webhookToken);
  }

  public static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
