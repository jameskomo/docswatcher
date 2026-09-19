package dev.docswatcher.app.support;

import dev.docswatcher.app.github.WebhookSignature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

public final class Webhooks {

  public static final String SECRET = "test-secret";

  private Webhooks() {}

  public static byte[] payload(String name) throws IOException {
    return new ClassPathResource("webhooks/" + name).getContentAsByteArray();
  }

  public static ResultActions post(MockMvc mvc, String event, byte[] body) throws Exception {
    return post(mvc, event, body, WebhookSignature.compute(SECRET, body), UUID.randomUUID().toString());
  }

  public static ResultActions post(MockMvc mvc, String event, byte[] body, String signature, String delivery) throws Exception {
    return mvc.perform(MockMvcRequestBuilders.post("/webhooks/github")
        .header("X-GitHub-Event", event)
        .header("X-GitHub-Delivery", delivery)
        .header("X-Hub-Signature-256", signature)
        .contentType("application/json")
        .content(body));
  }

  public static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
