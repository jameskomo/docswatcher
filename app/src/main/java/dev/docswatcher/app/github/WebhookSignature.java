package dev.docswatcher.app.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** GitHub signs webhook bodies with HMAC SHA-256 and sends the hex digest in X-Hub-Signature-256. */
public final class WebhookSignature {

  private WebhookSignature() {}

  public static String compute(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean verify(String secret, byte[] body, String header) {
    if (secret == null || secret.isBlank() || header == null) {
      return false;
    }
    byte[] expected = compute(secret, body).getBytes(StandardCharsets.US_ASCII);
    byte[] actual = header.getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expected, actual);
  }
}
