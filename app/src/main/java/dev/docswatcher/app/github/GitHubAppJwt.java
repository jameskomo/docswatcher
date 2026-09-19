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

  private static PrivateKey parse(String pem) {
    String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    } catch (Exception e) {
      throw new IllegalArgumentException("GITHUB_APP_PRIVATE_KEY must be a PKCS8 PEM", e);
    }
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
