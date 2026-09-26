package dev.docswatcher.app.alerts;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Signs the links in alert emails: confirm a subscription, and unsubscribe.
 *
 * <p>A token is its fields, base64url, a dot, and an HMAC-SHA256 of them. Nothing about a token is
 * stored, so an unsubscribe link keeps working in every email ever sent, and a confirmation link
 * carries the choice it confirms. The key is 256 random bits the app generates on first use and
 * keeps in the {@code signing_key} table: nothing to configure, and deleting the row revokes every
 * link at once.
 */
@Component
public class LinkSigner {

  private static final String KEY_NAME = "alert-links";
  private static final String SEPARATOR = "\n";
  static final int MAX_TOKEN = 2048;

  private final JdbcClient jdbc;
  private volatile byte[] key;

  public LinkSigner(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Signs the fields. A field may not contain a line break, which is the separator. */
  public String sign(String... fields) {
    for (String f : fields) {
      if (f.contains(SEPARATOR)) {
        throw new IllegalArgumentException("A signed field cannot contain a line break");
      }
    }
    byte[] payload = String.join(SEPARATOR, fields).getBytes(StandardCharsets.UTF_8);
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    return b64.encodeToString(payload) + "." + b64.encodeToString(mac(payload));
  }

  /** The fields of a token this app signed; empty for anything else, however malformed. */
  public Optional<List<String>> verify(String token) {
    if (token == null || token.length() > MAX_TOKEN) {
      return Optional.empty();
    }
    int dot = token.indexOf('.');
    if (dot <= 0 || dot != token.lastIndexOf('.')) {
      return Optional.empty();
    }
    try {
      Base64.Decoder b64 = Base64.getUrlDecoder();
      byte[] payload = b64.decode(token.substring(0, dot));
      byte[] signature = b64.decode(token.substring(dot + 1));
      if (!MessageDigest.isEqual(signature, mac(payload))) {
        return Optional.empty();
      }
      return Optional.of(Arrays.asList(new String(payload, StandardCharsets.UTF_8).split(SEPARATOR, -1)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private byte[] mac(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key(), "HmacSHA256"));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] key() {
    byte[] k = key;
    if (k == null) {
      synchronized (this) {
        if (key == null) {
          byte[] fresh = new byte[32];
          new SecureRandom().nextBytes(fresh);
          // Two instances starting together both insert; the first wins and both read the same row.
          jdbc.sql("insert into signing_key (name, secret) values (:n, :s) on conflict (name) do nothing")
              .param("n", KEY_NAME).param("s", fresh).update();
          key = jdbc.sql("select secret from signing_key where name = :n").param("n", KEY_NAME).query(byte[].class).single();
        }
        k = key;
      }
    }
    return k;
  }
}
