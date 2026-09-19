package dev.docswatcher.app.github;

import dev.docswatcher.app.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
public class WebhookController {

  private final AppProperties.GitHub github;
  private final WebhookService service;
  private final ObjectMapper mapper;

  public WebhookController(AppProperties properties, WebhookService service, ObjectMapper mapper) {
    this.github = properties.github();
    this.service = service;
    this.mapper = mapper;
  }

  @PostMapping("/webhooks/github")
  public ResponseEntity<String> receive(
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      @RequestHeader(value = "X-GitHub-Event", required = false) String event,
      @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
      @RequestBody byte[] body) {
    if (!WebhookSignature.verify(github.webhookSecret(), body, signature)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad signature");
    }
    if (delivery != null && !service.firstDelivery(delivery)) {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body("duplicate delivery");
    }
    JsonNode payload = mapper.readTree(body);
    service.handle(event == null ? "" : event, payload);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body("queued");
  }
}
