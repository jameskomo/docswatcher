package dev.docswatcher.app.alerts;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends one plain-text email through Brevo's transactional API.
 *
 * <p>Plain text only: there is no HTML part, so there is nowhere for an open-tracking pixel to go,
 * and nothing a recipient's mail program could render from data a repository put there. Every
 * message that can be unsubscribed from carries RFC 8058 List-Unsubscribe headers as well as the
 * link in its text.
 */
@Component
public class BrevoMailer {

  private static final Logger log = LoggerFactory.getLogger(BrevoMailer.class);

  /** One email. {@code unsubscribeUrl} may be null, for the confirmation email only. */
  public record Mail(String to, String subject, String text, String unsubscribeUrl) {}

  private final BrevoProperties properties;
  private final RestClient rest;
  private final Clock clock;
  private LocalDate day;
  private int sentToday;

  @Autowired
  public BrevoMailer(BrevoProperties properties) {
    this(properties, defaultClient(properties), Clock.systemUTC());
  }

  public BrevoMailer(BrevoProperties properties, RestClient rest, Clock clock) {
    this.properties = properties;
    this.rest = rest;
    this.clock = clock;
    if (!properties.configured()) {
      log.warn("Email alerts are off: docswatcher.brevo.api-key is not set");
    }
  }

  private static RestClient defaultClient(BrevoProperties properties) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(20));
    return RestClient.builder().requestFactory(factory).build();
  }

  public boolean configured() {
    return properties.configured();
  }

  /** Returns whether Brevo accepted the message. Never throws: a failed send is retried by the next run. */
  public boolean send(Mail mail) {
    if (!configured()) {
      return false;
    }
    if (!reserve()) {
      log.warn("Email not sent: the daily limit of {} is reached; it will go with the next run", properties.dailyLimit());
      return false;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sender", Map.of("name", properties.senderName(), "email", properties.senderEmail()));
    body.put("to", List.of(Map.of("email", mail.to())));
    body.put("subject", mail.subject());
    body.put("textContent", mail.text());
    if (mail.unsubscribeUrl() != null) {
      body.put("headers", Map.of(
          "List-Unsubscribe", "<" + mail.unsubscribeUrl() + ">",
          "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
    }
    try {
      rest.post()
          .uri(stripSlash(properties.apiBase()) + "/v3/smtp/email")
          .header("api-key", properties.apiKey().strip())
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RestClientResponseException e) {
      // The status is enough to act on; the response body can echo the address, so it stays out of the log.
      log.warn("Brevo refused an email: HTTP {}", e.getStatusCode().value());
      release();
      return false;
    } catch (RestClientException e) {
      log.warn("Brevo could not be reached: {}", e.getClass().getSimpleName());
      release();
      return false;
    }
  }

  private synchronized boolean reserve() {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    if (!today.equals(day)) {
      day = today;
      sentToday = 0;
    }
    if (sentToday >= properties.dailyLimit()) {
      return false;
    }
    sentToday++;
    return true;
  }

  private synchronized void release() {
    if (sentToday > 0) {
      sentToday--;
    }
  }

  private static String stripSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
