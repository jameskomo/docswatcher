package dev.docswatcher.app.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

  private final byte[] body = "{\"zen\":\"Keep it logically awesome.\"}".getBytes(StandardCharsets.UTF_8);

  @Test
  void computesGitHubStyleHexDigest() {
    String sig = WebhookSignature.compute("It's a Secret to Everybody", "Hello, World!".getBytes(StandardCharsets.UTF_8));
    assertThat(sig).isEqualTo("sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
  }

  @Test
  void verifiesMatchingSignature() {
    assertThat(WebhookSignature.verify("s3cret", body, WebhookSignature.compute("s3cret", body))).isTrue();
  }

  @Test
  void rejectsWrongSecretMissingHeaderAndBlankSecret() {
    assertThat(WebhookSignature.verify("other", body, WebhookSignature.compute("s3cret", body))).isFalse();
    assertThat(WebhookSignature.verify("s3cret", body, null)).isFalse();
    assertThat(WebhookSignature.verify("", body, "sha256=00")).isFalse();
  }
}
