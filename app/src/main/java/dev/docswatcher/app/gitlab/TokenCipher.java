package dev.docswatcher.app.gitlab;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Encrypts the GitLab access tokens DocsWatcher has to spend, so the database never holds one in
 * plain text (docs/adr/0011-gitlab.md).
 *
 * <p>AES-256-GCM under a key read from a secret file, a fresh 96-bit nonce per token, and the
 * connection's id as associated data: a ciphertext copied onto another connection's row does not
 * decrypt. The stored value is a version byte, the nonce, then the ciphertext and its tag.
 *
 * <p>A token that only had to be checked would be hashed instead, as the webhook token is. These
 * are presented to GitLab on every clone and API call, so they must come back out.
 */
@Component
public class TokenCipher {

  private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
  private static final byte VERSION = 1;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The key is missing or unusable. The message says which, and never contains the key. */
  public static class KeyUnavailable extends IllegalStateException {
    KeyUnavailable(String message) {
      super(message);
    }
  }

  private final SecretKey key;
  private final String problem;

  @Autowired
  public TokenCipher(GitLabProperties properties) {
    this(properties.tokenKey());
  }

  TokenCipher(String base64Key) {
    SecretKey parsed = null;
    String why = null;
    if (base64Key == null || base64Key.isBlank()) {
      why = "docswatcher.gitlab.token-key is not set";
    } else {
      try {
        byte[] raw = Base64.getDecoder().decode(base64Key.strip());
        if (raw.length != 32) {
          why = "docswatcher.gitlab.token-key must be base64 of 32 bytes, not " + raw.length;
        } else {
          parsed = new SecretKeySpec(raw, "AES");
        }
      } catch (IllegalArgumentException e) {
        why = "docswatcher.gitlab.token-key is not base64";
      }
    }
    this.key = parsed;
    this.problem = why;
    if (why != null && base64Key != null && !base64Key.isBlank()) {
      log.error("{}. GitLab groups cannot be connected or scanned until it is fixed.", why);
    }
  }

  public boolean available() {
    return key != null;
  }

  /** Why {@link #available()} is false, for an operator; null when it is true. */
  public String problem() {
    return problem;
  }

  public byte[] encrypt(String token, long connectionId) {
    requireKey();
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(connectionId));
      byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length).put(VERSION).put(nonce).put(sealed).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot encrypt a GitLab token", e);
    }
  }

  public String decrypt(byte[] stored, long connectionId) {
    requireKey();
    if (stored == null || stored.length < 1 + NONCE_BYTES + TAG_BITS / 8 || stored[0] != VERSION) {
      throw new IllegalStateException("Stored GitLab token of connection " + connectionId + " is not in a known format");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
      cipher.updateAAD(aad(connectionId));
      byte[] plain = cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      // A different key, a tampered row, or a row copied from another connection.
      throw new IllegalStateException("Stored GitLab token of connection " + connectionId + " does not decrypt under the configured key");
    }
  }

  private void requireKey() {
    if (key == null) {
      throw new KeyUnavailable(problem);
    }
  }

  private static byte[] aad(long connectionId) {
    return ("docswatcher-gitlab-connection:" + connectionId).getBytes(StandardCharsets.US_ASCII);
  }
}
