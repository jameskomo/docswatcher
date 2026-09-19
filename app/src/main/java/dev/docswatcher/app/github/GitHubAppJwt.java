package dev.docswatcher.app.github;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/** Signs the short-lived RS256 JWT a GitHub App uses to mint installation tokens. */
public final class GitHubAppJwt {

  private final String appId;
  private final PrivateKey key;

  public GitHubAppJwt(String appId, String pkcs8Pem) {
    this.appId = appId;
    this.key = parse(pkcs8Pem);
  }

  public String sign(Instant now) {
    String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    long iat = now.getEpochSecond() - 60;
    long exp = now.getEpochSecond() + 540;
    String payload = b64(("{\"iat\":" + iat + ",\"exp\":" + exp + ",\"iss\":\"" + appId + "\"}").getBytes(StandardCharsets.UTF_8));
    String signingInput = header + "." + payload;
    try {
      Signature sig = Signature.getInstance("SHA256withRSA");
      sig.initSign(key);
      sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + b64(sig.sign());
    } catch (Exception e) {
      throw new IllegalStateException("Cannot sign GitHub App JWT", e);
    }
  }

  /**
   * Accepts the key file exactly as GitHub hands it over.
   *
   * <p>GitHub's download is PKCS#1, which opens with BEGIN RSA PRIVATE KEY. Java's
   * KeyFactory only understands PKCS#8, which opens with BEGIN PRIVATE KEY. Requiring
   * the operator to run openssl first is a step that is easy to get wrong and easy to
   * skip, so the wrapping is done here instead. Both formats are accepted.
   */
  private static PrivateKey parse(String pem) {
    boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
    String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    try {
      byte[] der = Base64.getDecoder().decode(body);
      if (pkcs1) der = wrapPkcs1(der);
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "GITHUB_APP_PRIVATE_KEY must be an RSA private key in PEM form, either the file "
              + "GitHub downloads or a PKCS8 conversion of it",
          e);
    }
  }

  /** Wraps a PKCS#1 RSAPrivateKey in the PKCS#8 PrivateKeyInfo structure Java expects. */
  private static byte[] wrapPkcs1(byte[] pkcs1) {
    byte[] version = {0x02, 0x01, 0x00};
    // AlgorithmIdentifier for rsaEncryption, 1.2.840.113549.1.1.1, with NULL parameters.
    byte[] algorithm = {
      0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
      (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };
    byte[] key = der((byte) 0x04, pkcs1);
    byte[] contents = new byte[version.length + algorithm.length + key.length];
    System.arraycopy(version, 0, contents, 0, version.length);
    System.arraycopy(algorithm, 0, contents, version.length, algorithm.length);
    System.arraycopy(key, 0, contents, version.length + algorithm.length, key.length);
    return der((byte) 0x30, contents);
  }

  /** One DER element: tag, definite length, contents. */
  private static byte[] der(byte tag, byte[] contents) {
    int n = contents.length;
    byte[] length;
    if (n < 0x80) {
      length = new byte[] {(byte) n};
    } else {
      int bytes = 0;
      for (int v = n; v > 0; v >>>= 8) bytes++;
      length = new byte[bytes + 1];
      length[0] = (byte) (0x80 | bytes);
      for (int i = 0; i < bytes; i++) length[bytes - i] = (byte) (n >>> (8 * i));
    }
    byte[] out = new byte[1 + length.length + n];
    out[0] = tag;
    System.arraycopy(length, 0, out, 1, length.length);
    System.arraycopy(contents, 0, out, 1 + length.length, n);
    return out;
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
