package dev.docswatcher.app.support;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.hamcrest.Matchers.startsWith;

import dev.docswatcher.app.alerts.BrevoMailer;
import dev.docswatcher.app.alerts.BrevoProperties;
import dev.docswatcher.app.alerts.SlackNotifier;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real {@link BrevoMailer} and {@link SlackNotifier}, talking to MockRestServiceServers that
 * record what was sent. Import it into a test; call {@link #reset} before each one.
 */
@TestConfiguration
public class AlertMocks {

  public static final String BREVO = "https://api.brevo.test";

  /** What was posted, in order. */
  public static class Outbox {
    public final List<JsonNode> emails = new CopyOnWriteArrayList<>();
    public final List<String> brevoKeys = new CopyOnWriteArrayList<>();
    public final List<String> slack = new CopyOnWriteArrayList<>();
    public final List<String> slackUrls = new CopyOnWriteArrayList<>();
    final MockRestServiceServer brevo;
    final MockRestServiceServer slackServer;
    private final ObjectMapper mapper = new ObjectMapper();

    Outbox(MockRestServiceServer brevo, MockRestServiceServer slack) {
      this.brevo = brevo;
      this.slackServer = slack;
    }

    /** Forgets what was sent; Brevo and Slack then answer with these statuses. */
    public void reset(HttpStatus brevoStatus, HttpStatus slackStatus) {
      emails.clear();
      brevoKeys.clear();
      slack.clear();
      slackUrls.clear();
      brevo.reset();
      slackServer.reset();
      brevo.expect(ExpectedCount.manyTimes(), requestTo(BREVO + "/v3/smtp/email"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(request -> {
            brevoKeys.add(request.getHeaders().getFirst("api-key"));
            emails.add(mapper.readTree(((MockClientHttpRequest) request).getBodyAsString()));
          })
          .andRespond(withStatus(brevoStatus).contentType(MediaType.APPLICATION_JSON).body("{\"messageId\":\"<1@brevo.test>\"}"));
      slackServer.expect(ExpectedCount.manyTimes(), requestTo(startsWith("https://hooks.slack.com/services/")))
          .andExpect(method(HttpMethod.POST))
          .andExpect(request -> {
            slackUrls.add(request.getURI().toString());
            slack.add(mapper.readTree(((MockClientHttpRequest) request).getBodyAsString()).path("text").asString());
          })
          .andRespond(withStatus(slackStatus).body("ok"));
    }

    public void reset() {
      reset(HttpStatus.CREATED, HttpStatus.OK);
    }

    public List<JsonNode> emailsTo(String address) {
      return emails.stream().filter(e -> e.path("to").path(0).path("email").asString().equals(address)).toList();
    }
  }

  private final RestClient.Builder brevoBuilder = RestClient.builder();
  private final RestClient.Builder slackBuilder = RestClient.builder();
  private final Outbox outbox = new Outbox(
      MockRestServiceServer.bindTo(brevoBuilder).build(), MockRestServiceServer.bindTo(slackBuilder).build());

  @Bean
  Outbox alertOutbox() {
    return outbox;
  }

  @Bean
  @Primary
  BrevoMailer mockedBrevoMailer() {
    return new BrevoMailer(new BrevoProperties("brevo-test-key", BREVO, "alerts@vukisha.co.ke", "DocsWatcher", 300),
        brevoBuilder.build(), Clock.systemUTC());
  }

  @Bean
  @Primary
  SlackNotifier mockedSlackNotifier() {
    return new SlackNotifier(slackBuilder.build());
  }
}
