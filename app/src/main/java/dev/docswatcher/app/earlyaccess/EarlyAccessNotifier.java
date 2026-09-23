package dev.docswatcher.app.earlyaccess;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Emails the owner about each early-access request, through the notify/ worker. The request is
 * already stored when this runs, so a failure here is logged and never reaches the visitor.
 */
@Component
public class EarlyAccessNotifier {

  private static final Logger log = LoggerFactory.getLogger(EarlyAccessNotifier.class);
  private static final int ATTEMPTS = 3;

  /** What the owner is told. */
  public record Lead(String email, String company, String repositories, String providers, String interest,
      String message, int requests) {}

  private final RestClient rest;
  private final boolean configured;

  public EarlyAccessNotifier(@Value("${docswatcher.notify.url:}") String url,
      @Value("${docswatcher.notify.token:}") String token) {
    this.configured = !url.isBlank() && !token.isBlank();
    var factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofSeconds(10));
    this.rest = RestClient.builder().requestFactory(factory).baseUrl(configured ? url : "http://unset.invalid")
        .defaultHeader("Authorization", "Bearer " + token.strip()).build();
    if (!configured) log.warn("Early-access emails are off: docswatcher.notify.url or token is not set");
  }

  /** Sends in the background, so the visitor never waits on email. */
  public void tell(Lead lead) {
    if (configured) Thread.ofVirtual().name("early-access-notify").start(() -> send(lead));
  }

  /** Returns whether it was sent. A few tries, because a lead missed is worth more than a retry. */
  boolean send(Lead lead) {
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        rest.post().contentType(MediaType.APPLICATION_JSON).body(lead).retrieve().toBodilessEntity();
        return true;
      } catch (RuntimeException e) {
        log.warn("Early-access email attempt {} of {} failed: {}", attempt, ATTEMPTS, e.getMessage());
        sleep(attempt);
      }
    }
    log.error("Early-access email not sent; the request is stored. Read it with GET /api/early-access");
    return false;
  }

  private static void sleep(int attempt) {
    try {
      Thread.sleep(Duration.ofSeconds(2L * attempt));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
