package dev.docswatcher.app.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.support.AlertMocks;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.PostgresTest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Public email alerts from the calendar page: double opt-in, one-click unsubscribe, and the daily digest. */
@AutoConfigureMockMvc
@Import(AlertMocks.class)
class SubscriptionIT extends PostgresTest {

  static final String SITE = "https://docswatcher.test";
  static final LocalDate DAY30 = FakeScanEngine.EFFECTIVE.minusDays(30);
  private static final AtomicInteger CLIENTS = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired SubscriberStore store;
  @Autowired AlertJob job;
  @Autowired LinkSigner signer;
  @Autowired AlertMocks.Outbox outbox;

  private String client;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from subscriber").update();
    jdbc.sql("delete from alert_settings").update();
    client = "198.51.100." + CLIENTS.incrementAndGet();
    outbox.reset();
  }

  private ResultActions subscribe(String json) throws Exception {
    return mvc.perform(post("/subscribe").header("CF-Connecting-IP", client).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private int rows() {
    return jdbc.sql("select count(*) from subscriber").query(Integer.class).single();
  }

  /** The path and query of the one link in the last email to this address that starts with {@code path}. */
  private String link(String address, String path) {
    List<tools.jackson.databind.JsonNode> mails = outbox.emailsTo(address);
    assertThat(mails).isNotEmpty();
    String text = mails.get(mails.size() - 1).path("textContent").asString();
    Matcher m = Pattern.compile(Pattern.quote(SITE) + "(" + Pattern.quote(path) + "\\?token=[A-Za-z0-9_.-]+)").matcher(text);
    assertThat(m.find()).as("a %s link in %s", path, text).isTrue();
    return m.group(1);
  }

  private static String token(String pathAndQuery) {
    return pathAndQuery.substring(pathAndQuery.indexOf("token=") + 6);
  }

  @Test
  void nothing_is_sent_until_the_address_is_confirmed() throws Exception {
    subscribe("{\"email\":\"Dev@Example.test\",\"providers\":[\"openai\"]}").andExpect(status().isCreated()).andExpect(jsonPath("$.ok").value(true));
    assertThat(outbox.emails).hasSize(1);
    var confirmation = outbox.emails.get(0);
    assertThat(confirmation.path("to").path(0).path("email").asString()).isEqualTo("dev@example.test");
    assertThat(confirmation.path("subject").asString()).isEqualTo("Confirm your DocsWatcher alerts");
    assertThat(confirmation.path("textContent").asString()).contains("OpenAI");
    assertThat(store.find("dev@example.test").orElseThrow().confirmedAt()).isNull();

    // The job skips an unconfirmed address.
    outbox.reset();
    assertThat(job.run(DAY30).subscribers().emails()).isZero();
    assertThat(outbox.emails).isEmpty();
  }

  @Test
  void the_confirmation_link_opens_a_page_and_only_its_button_subscribes() throws Exception {
    subscribe("{\"email\":\"dev@example.test\",\"providers\":[\"openai\"]}").andExpect(status().isCreated());
    String confirm = link("dev@example.test", "/subscribe/confirm");

    // A mail scanner fetching the link changes nothing.
    mvc.perform(get(confirm))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("<form method=\"post\" action=\"confirm\">")))
        .andExpect(header().string("Content-Security-Policy", Matchers.containsString("default-src 'none'")))
        .andExpect(header().string("Referrer-Policy", "no-referrer"));
    assertThat(store.find("dev@example.test").orElseThrow().confirmedAt()).isNull();

    mvc.perform(post("/subscribe/confirm").param("token", token(confirm))).andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("You are subscribed")));
    SubscriberStore.Subscriber s = store.find("dev@example.test").orElseThrow();
    assertThat(s.confirmedAt()).isNotNull();
    assertThat(s.providers()).containsExactly("openai");
  }

  @Test
  void a_confirmed_subscriber_gets_one_digest_per_threshold_with_a_one_click_unsubscribe() throws Exception {
    store.confirm("dev@example.test", List.of());
    AlertJob.Summary s = job.run(DAY30);
    assertThat(s.subscribers().emails()).isEqualTo(1);
    var mail = outbox.emailsTo("dev@example.test").get(0);
    assertThat(mail.has("htmlContent")).isFalse();
    assertThat(mail.path("subject").asString()).isEqualTo("DocsWatcher: gpt-4-turbo shut down, in 30 days");
    assertThat(mail.path("textContent").asString())
        .contains("In 30 days, 2026-10-23: OpenAI, gpt-4-turbo shut down (breaking)")
        .contains("Requests fail after shutdown.")
        .contains(SITE + "/#/calendar");
    assertThat(mail.path("headers").path("List-Unsubscribe").asString()).startsWith("<" + SITE + "/unsubscribe?token=");

    assertThat(job.run(DAY30).subscribers().emails()).isZero();
    assertThat(job.run(DAY30.plusDays(5)).subscribers().emails()).isZero();
    assertThat(job.run(FakeScanEngine.EFFECTIVE.minusDays(7)).subscribers().emails()).isEqualTo(1);

    String unsubscribe = link("dev@example.test", "/unsubscribe");
    mvc.perform(get(unsubscribe)).andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("You are unsubscribed")));
    assertThat(store.find("dev@example.test")).isEmpty();
    assertThat(jdbc.sql("select count(*) from subscriber_sent").query(Integer.class).single()).isZero();
  }

  @Test
  void mail_programs_can_unsubscribe_without_a_click() throws Exception {
    store.confirm("dev@example.test", List.of());
    String token = signer.sign(AlertLinks.SUBSCRIBER, "dev@example.test");
    mvc.perform(post("/unsubscribe").param("token", token).contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .content("List-Unsubscribe=One-Click")).andExpect(status().isOk());
    assertThat(store.find("dev@example.test")).isEmpty();
  }

  @Test
  void a_provider_filter_is_respected() {
    store.confirm("stripe-only@example.test", List.of("slack"));
    assertThat(job.run(DAY30).subscribers().emails()).isZero();
  }

  @Test
  void a_second_request_within_the_hour_sends_no_second_email_and_says_the_same() throws Exception {
    subscribe("{\"email\":\"dev@example.test\"}").andExpect(status().isCreated());
    subscribe("{\"email\":\"dev@example.test\",\"providers\":[\"slack\"]}").andExpect(status().isCreated());
    assertThat(outbox.emails).hasSize(1);
    assertThat(rows()).isEqualTo(1);
  }

  @Test
  void a_new_choice_for_a_confirmed_address_waits_for_its_own_confirmation() throws Exception {
    store.confirm("dev@example.test", List.of("openai"));
    subscribe("{\"email\":\"dev@example.test\",\"providers\":[\"slack\"]}").andExpect(status().isCreated());
    assertThat(store.find("dev@example.test").orElseThrow().providers()).containsExactly("openai");
    mvc.perform(post("/subscribe/confirm").param("token", token(link("dev@example.test", "/subscribe/confirm")))).andExpect(status().isOk());
    assertThat(store.find("dev@example.test").orElseThrow().providers()).containsExactly("slack");
  }

  @Test
  void the_form_is_validated_honeypotted_and_rate_limited() throws Exception {
    subscribe("{\"email\":\"not an email\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
    subscribe("{\"email\":\"a@b.test\",\"providers\":[\"nope\"]}").andExpect(status().isBadRequest());
    subscribe("{\"email\":\"bot@spam.test\",\"website\":\"http://spam.test\"}").andExpect(status().isCreated());
    assertThat(rows()).isZero();
    assertThat(outbox.emails).isEmpty();
    for (int i = 3; i < SubscriptionController.PER_WINDOW; i++) {
      subscribe("{\"email\":\"r" + i + "@example.test\"}").andExpect(status().isCreated());
    }
    subscribe("{\"email\":\"over@example.test\"}").andExpect(status().isTooManyRequests());
  }

  @Test
  void forged_expired_and_misused_tokens_do_nothing() throws Exception {
    store.confirm("dev@example.test", List.of());
    mvc.perform(get("/unsubscribe").param("token", "bogus")).andExpect(status().isBadRequest());
    mvc.perform(get("/unsubscribe")).andExpect(status().isBadRequest());
    String good = signer.sign(AlertLinks.SUBSCRIBER, "dev@example.test");
    String forged = good.substring(0, good.indexOf('.')) + ".AAAA" + good.substring(good.indexOf('.') + 5);
    mvc.perform(get("/unsubscribe").param("token", forged)).andExpect(status().isBadRequest());
    // A confirmation token is not an unsubscribe token, nor the other way round.
    String confirm = signer.sign(AlertLinks.CONFIRM, "dev@example.test", "", Long.toString(LocalDate.now().plusDays(1).toEpochDay()));
    mvc.perform(get("/unsubscribe").param("token", confirm)).andExpect(status().isBadRequest());
    mvc.perform(post("/subscribe/confirm").param("token", good)).andExpect(status().isBadRequest());
    assertThat(store.find("dev@example.test")).isPresent();

    String expired = signer.sign(AlertLinks.CONFIRM, "late@example.test", "", Long.toString(LocalDate.now().minusDays(1).toEpochDay()));
    mvc.perform(get("/subscribe/confirm").param("token", expired)).andExpect(status().isBadRequest());
    mvc.perform(post("/subscribe/confirm").param("token", expired)).andExpect(status().isBadRequest());
    assertThat(store.find("late@example.test")).isEmpty();
  }

  @Test
  void an_address_never_confirmed_is_forgotten_after_a_week() {
    jdbc.sql("insert into subscriber (email, created_at, confirm_sent_at) values ('old@example.test', now() - interval '8 days', now() - interval '8 days')").update();
    jdbc.sql("insert into subscriber (email) values ('new@example.test')").update();
    job.run(DAY30);
    assertThat(store.find("old@example.test")).isEmpty();
    assertThat(store.find("new@example.test")).isPresent();
  }
}
