package dev.docswatcher.app.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The parts of alerts that need no database. */
class AlertUnitsTest {

  @Test
  void the_nearest_crossed_threshold_is_due_once() {
    List<Integer> t = Thresholds.DEFAULT;
    assertThat(Thresholds.due(31, t, Set.of())).isEmpty();
    assertThat(Thresholds.due(30, t, Set.of())).isEqualTo(OptionalInt.of(30));
    assertThat(Thresholds.due(29, t, Set.of(30))).isEmpty();
    assertThat(Thresholds.due(7, t, Set.of(30))).isEqualTo(OptionalInt.of(7));
    assertThat(Thresholds.due(3, t, Set.of())).isEqualTo(OptionalInt.of(7));
    assertThat(Thresholds.due(3, t, Set.of(7))).isEmpty();
    assertThat(Thresholds.due(0, t, Set.of(30))).isEqualTo(OptionalInt.of(7));
    assertThat(Thresholds.due(-1, t, Set.of())).isEmpty();
    assertThat(Thresholds.crossed(5, t)).containsExactly(7, 30);
  }

  @Test
  void thresholds_are_checked_and_ordered() {
    assertThat(Thresholds.normalise(List.of(7, 30, 7, 14))).containsExactly(30, 14, 7);
    assertThatThrownBy(() -> Thresholds.normalise(List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Thresholds.normalise(List.of(366))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Thresholds.normalise(List.of(1, 2, 3, 4, 5))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void only_a_plain_hooks_slack_com_webhook_is_accepted() {
    assertThat(SlackNotifier.validWebhook("https://hooks.slack.com/services/T0001/B0001/abcDEF123")).isTrue();
    for (String bad : List.of("", "http://hooks.slack.com/services/T/B/x", "https://hooks.slack.com/services/T/B",
        "https://hooks.slack.com/services/T/B/x/", "https://hooks.slack.com/workflows/T/B/x", "https://HOOKS.slack.com/services/T/B/x",
        "https://hooks.slack.com%2e.evil.test/services/T/B/x", "https://hooks.slack.com/services/T/B/x#y",
        "https://hooks.slack.com/services/T/B/x\n", "https://user@hooks.slack.com/services/T/B/x")) {
      assertThat(SlackNotifier.validWebhook(bad)).as(bad).isFalse();
    }
    assertThat(SlackNotifier.validWebhook(null)).isFalse();
  }

  @Test
  void slack_text_cannot_become_a_link_or_a_mention() {
    String text = AlertText.teamSlack("acme<!channel>", List.of(new AlertText.Item("x <https://evil.test|click>", "P&Q", "breaking",
        LocalDate.of(2026, 10, 23), 7, null, null, List.of(new AlertText.Place("acme/<!here>", "c", List.of())))), "https://site.test/#/app");
    assertThat(text).doesNotContain("<!channel>").doesNotContain("<!here>").doesNotContain("<https://evil.test")
        .contains("acme&lt;!channel&gt;").contains("P&amp;Q").endsWith("<https://site.test/#/app|Open the dashboard>");
  }

  @Test
  void only_web_links_from_the_knowledge_base_are_used() {
    assertThat(AlertText.isWebLink("https://platform.openai.com/docs/deprecations")).isTrue();
    assertThat(AlertText.isWebLink("javascript:alert(1)")).isFalse();
    assertThat(AlertText.isWebLink("https://a.test/x y")).isFalse();
    assertThat(AlertText.isWebLink(null)).isFalse();
  }

  private static BrevoProperties brevo(String key, int limit) {
    return new BrevoProperties(key, "https://api.brevo.test/", "alerts@vukisha.co.ke", "DocsWatcher", limit);
  }

  @Test
  void brevo_is_sent_plain_text_with_the_key_and_the_unsubscribe_headers() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://api.brevo.test/v3/smtp/email"))
        .andExpect(header("api-key", "k3y"))
        .andExpect(jsonPath("$.sender.email").value("alerts@vukisha.co.ke"))
        .andExpect(jsonPath("$.sender.name").value("DocsWatcher"))
        .andExpect(jsonPath("$.to[0].email").value("a@b.test"))
        .andExpect(jsonPath("$.textContent").value("Hello"))
        .andExpect(jsonPath("$.htmlContent").doesNotExist())
        .andExpect(jsonPath("$.headers.List-Unsubscribe").value("<https://site.test/unsubscribe?token=t>"))
        .andRespond(withStatus(HttpStatus.CREATED));
    BrevoMailer mailer = new BrevoMailer(brevo("k3y", 300), builder.build(), Clock.systemUTC());
    assertThat(mailer.send(new BrevoMailer.Mail("a@b.test", "Hi", "Hello", "https://site.test/unsubscribe?token=t"))).isTrue();
    server.verify();
  }

  @Test
  void brevo_failures_and_the_daily_limit_return_false_and_never_throw() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(ExpectedCount.times(2), requestTo(Matchers.startsWith("https://api.brevo.test/"))).andRespond(withStatus(HttpStatus.BAD_REQUEST));
    BrevoMailer mailer = new BrevoMailer(brevo("k", 1), builder.build(), Clock.systemUTC());
    BrevoMailer.Mail mail = new BrevoMailer.Mail("a@b.test", "s", "t", null);
    assertThat(mailer.send(mail)).isFalse();
    // A refused send does not count against the limit.
    assertThat(mailer.send(mail)).isFalse();
    server.verify();

    RestClient.Builder ok = RestClient.builder();
    MockRestServiceServer okServer = MockRestServiceServer.bindTo(ok).build();
    okServer.expect(ExpectedCount.once(), requestTo(Matchers.startsWith("https://api.brevo.test/"))).andRespond(withStatus(HttpStatus.CREATED));
    BrevoMailer limited = new BrevoMailer(brevo("k", 1), ok.build(), Clock.systemUTC());
    assertThat(limited.send(mail)).isTrue();
    assertThat(limited.send(mail)).isFalse();
    okServer.verify();
  }

  @Test
  void without_a_key_nothing_is_sent() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    BrevoMailer mailer = new BrevoMailer(brevo("", 300), builder.build(), Clock.systemUTC());
    assertThat(mailer.configured()).isFalse();
    assertThat(mailer.send(new BrevoMailer.Mail("a@b.test", "s", "t", null))).isFalse();
    server.verify();
  }
}
