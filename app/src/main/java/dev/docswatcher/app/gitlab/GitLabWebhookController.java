package dev.docswatcher.app.gitlab;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitLab's project and group webhooks. GitLab does not sign bodies; it sends back the secret
 * token the hook was configured with, in {@code X-Gitlab-Token}, and that token is what
 * authenticates the request (docs/adr/0012-gitlab.md).
 */
@RestController
public class GitLabWebhookController {

  private final GitLabWebhookService service;
  private final ObjectMapper mapper;

  public GitLabWebhookController(GitLabWebhookService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @PostMapping("/webhooks/gitlab")
  public ResponseEntity<String> receive(
      @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestHeader(value = "X-Gitlab-Event-UUID", required = false) String eventUuid,
      @RequestBody byte[] body) {
    Optional<GitLabStore.Connection> connection = service.authenticate(token);
    if (connection.isEmpty()) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad token");
    }
    // Idempotency-Key stays the same across GitLab's retries of one delivery (GitLab 17.4 and
    // later); older instances send only the event's UUID. With neither, the event is handled:
    // a push queued twice scans twice, and the issue states it sets are idempotent.
    String delivery = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : eventUuid;
    if (delivery != null && !delivery.isBlank() && delivery.length() <= 200 && !service.firstDelivery(delivery)) {
      return ResponseEntity.status(HttpStatus.ACCEPTED).body("duplicate delivery");
    }
    JsonNode payload = mapper.readTree(body);
    service.handle(connection.get(), payload);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body("queued");
  }
}
