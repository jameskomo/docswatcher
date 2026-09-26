package dev.docswatcher.app.alerts;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
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
 * Posts to a Slack incoming webhook.
 *
 * <p>A webhook URL is typed in by a customer and then fetched by the server, which is how
 * server-side request forgery starts. So only one shape is accepted, checked when it is saved and
 * again before every post: https, the host {@code hooks.slack.com} exactly, no port, no user info,
 * the {@code /services/} path with three plain tokens, no query and no fragment. Redirects are not
 * followed, so Slack cannot be used as a bounce to anywhere else either.
 */
@Component
public class SlackNotifier {

  private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

  static final Pattern WEBHOOK =
      Pattern.compile("^https://hooks\\.slack\\.com/services/[A-Za-z0-9]{1,40}/[A-Za-z0-9]{1,40}/[A-Za-z0-9]{1,80}$");

  private final RestClient rest;

  @Autowired
  public SlackNotifier() {
    this(defaultClient());
  }

  public SlackNotifier(RestClient rest) {
    this.rest = rest;
  }

  private static RestClient defaultClient() {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(15));
    return RestClient.builder().requestFactory(factory).build();
  }

  public static boolean validWebhook(String url) {
    return url != null && url.length() <= 200 && WEBHOOK.matcher(url).matches();
  }

  /** Slack's mrkdwn treats these three as control characters: escaped, text cannot become a link or a mention. */
  public static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Returns whether Slack accepted the message. Never throws. */
  public boolean post(String webhook, String text) {
    if (!validWebhook(webhook)) {
      log.warn("Slack message not sent: the stored webhook is not a hooks.slack.com URL");
      return false;
    }
    try {
      rest.post()
          .uri(URI.create(webhook))
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("text", text))
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RestClientResponseException e) {
      log.warn("Slack refused a message: HTTP {}", e.getStatusCode().value());
      return false;
    } catch (RestClientException e) {
      log.warn("Slack could not be reached: {}", e.getClass().getSimpleName());
      return false;
    }
  }
}
