package dev.docswatcher.app.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.auth.Cookies;
import dev.docswatcher.app.auth.SessionStore;
import dev.docswatcher.app.auth.UserSession;
import dev.docswatcher.app.store.ContractStore;
import dev.docswatcher.app.store.FindingStore;
import dev.docswatcher.app.store.InstallationStore;
import dev.docswatcher.app.store.RepoStore;
import dev.docswatcher.app.store.StoredContract;
import dev.docswatcher.app.support.AlertMocks;
import dev.docswatcher.app.support.FakeScanEngine;
import dev.docswatcher.app.support.PostgresTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Warned before the date, against a real database with Brevo and Slack mocked. acme has three
 * repositories with the same finding 30 days out on {@link #DAY30}; the member reads one and
 * writes another. globex exists and the member cannot see it.
 */
@AutoConfigureMockMvc
@Import(AlertMocks.class)
class TeamAlertsIT extends PostgresTest {

  static final String SITE = "https://docswatcher.test";
  static final String HOOK = "https://hooks.slack.com/services/T0001/B0001/abcdefghijklmnopqrstuvwx";
  static final LocalDate DAY30 = FakeScanEngine.EFFECTIVE.minusDays(30);
  static final String EVIDENCE = "[{\"path\":\"src/app.py\",\"line\":3,\"column\":1,\"snippet\":\"model='gpt-4-turbo'\",\"detector\":\"d\",\"layer\":\"literal\"}]";

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired InstallationStore installations;
  @Autowired RepoStore repos;
  @Autowired ContractStore contracts;
  @Autowired FindingStore findings;
  @Autowired SessionStore sessions;
  @Autowired AlertStore alerts;
  @Autowired AlertJob job;
  @Autowired AlertMocks.Outbox outbox;

  Cookie reader;
  Cookie writer;

  @BeforeEach
  void seed() {
    jdbc.sql("delete from installation").update();
    jdbc.sql("delete from user_session").update();
    jdbc.sql("delete from subscriber").update();
    installations.upsert(5001, "acme");
    installations.upsert(6001, "globex");
    for (long[] r : new long[][] {{9001, 5001}, {9002, 5001}, {9003, 5001}, {9101, 6001}}) {
      String name = (r[1] == 5001 ? "acme/" : "globex/") + "repo" + r[0];
      repos.upsert(r[0], r[1], name, "main");
      repos.setLastScannedSha(r[0], "sha" + r[0]);
      contracts.upsert(new StoredContract(r[0], FakeScanEngine.CONTRACT_ID, "openai", "model", "gpt-4-turbo", "high", EVIDENCE, null, "sha" + r[0], "sha" + r[0]));
      findings.insertOpen(r[0], FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "f" + r[0], "breaking", FakeScanEngine.EFFECTIVE);
    }
    reader = session(List.of(new UserSession.RepoAccess(9001, "acme/repo9001", "read")));
    writer = session(List.of(new UserSession.RepoAccess(9001, "acme/repo9001", "read"), new UserSession.RepoAccess(9002, "acme/repo9002", "write")));
    outbox.reset();
  }

  private Cookie session(List<UserSession.RepoAccess> repos) {
    return new Cookie(Cookies.SESSION, sessions.create(42, "octo", "Octo", null, List.of(new UserSession.OrgAccess(5001, "acme", repos)), Duration.ofHours(1)));
  }

  private MockHttpServletRequestBuilder save(String login, Cookie who, String json) {
    return post("/api/orgs/" + login + "/alerts").cookie(who).header("Origin", SITE).contentType("application/json").content(json);
  }

  private void configure(boolean enabled, List<String> emails, String slack) {
    alerts.save(new AlertStore.Settings(5001, enabled, emails, slack, Thresholds.DEFAULT, "test", null));
  }

  // Settings and who may change them.

  @Test
  void anyone_who_sees_the_organisation_reads_the_settings_but_only_writers_may_change_them() throws Exception {
    mvc.perform(get("/api/orgs/acme/alerts").cookie(reader))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.thresholds[0]").value(30))
        .andExpect(jsonPath("$.thresholds[1]").value(7))
        .andExpect(jsonPath("$.canEdit").value(false))
        .andExpect(jsonPath("$.emailAvailable").value(true));
    mvc.perform(save("acme", reader, "{\"emails\":[\"ops@acme.test\"]}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/orgs/acme/alerts/test").cookie(reader).header("Origin", SITE)).andExpect(status().isForbidden());
    assertThat(alerts.find(5001)).isEmpty();

    mvc.perform(get("/api/orgs/acme/alerts").cookie(writer)).andExpect(jsonPath("$.canEdit").value(true));
    mvc.perform(save("acme", writer, "{\"enabled\":true,\"emails\":[\" Ops@Acme.test \",\"ops@acme.test\",\"\"],\"slackWebhook\":\"" + HOOK + "\",\"thresholds\":[7,14,30]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emails.length()").value(1))
        .andExpect(jsonPath("$.emails[0]").value("ops@acme.test"))
        .andExpect(jsonPath("$.thresholds[0]").value(30))
        .andExpect(jsonPath("$.slack.configured").value(true))
        .andExpect(jsonPath("$.updatedBy").value("octo"));
    assertThat(alerts.find(5001).orElseThrow().slackWebhook()).isEqualTo(HOOK);
  }

  @Test
  void the_slack_webhook_is_never_sent_back() throws Exception {
    configure(true, List.of(), HOOK);
    String body = mvc.perform(get("/api/orgs/acme/alerts").cookie(reader)).andExpect(status().isOk())
        .andExpect(jsonPath("$.slack.hint").value("…uvwx")).andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("hooks.slack.com").doesNotContain("T0001");
    // Leaving it out keeps it; a blank removes it.
    mvc.perform(save("acme", writer, "{\"emails\":[]}")).andExpect(jsonPath("$.slack.configured").value(true));
    mvc.perform(save("acme", writer, "{\"slackWebhook\":\"\"}")).andExpect(jsonPath("$.slack.configured").value(false));
  }

  @Test
  void an_organisation_the_member_cannot_see_is_not_found_and_changes_need_the_site_origin() throws Exception {
    mvc.perform(get("/api/orgs/globex/alerts").cookie(writer)).andExpect(status().isNotFound());
    mvc.perform(save("globex", writer, "{\"emails\":[\"x@globex.test\"]}")).andExpect(status().isNotFound());
    mvc.perform(post("/api/orgs/acme/alerts").cookie(writer).contentType("application/json").content("{\"emails\":[]}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/orgs/acme/alerts").cookie(writer).header("Origin", "https://evil.test").contentType("application/json").content("{\"emails\":[]}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/orgs/acme/alerts")).andExpect(status().isUnauthorized());
    // The owner's token reaches every organisation.
    mvc.perform(post("/api/orgs/globex/alerts").header("Authorization", "Bearer test-token").contentType("application/json")
        .content("{\"emails\":[\"x@globex.test\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.updatedBy").value("owner"));
  }

  @Test
  void settings_are_validated_and_only_hooks_slack_com_is_accepted() throws Exception {
    for (String bad : List.of(
        "http://hooks.slack.com/services/T0001/B0001/abc",
        "https://hooks.slack.com.evil.test/services/T0001/B0001/abc",
        "https://evil.test/https://hooks.slack.com/services/T0001/B0001/abc",
        "https://hooks.slack.com@evil.test/services/T0001/B0001/abc",
        "https://hooks.slack.com:8443/services/T0001/B0001/abc",
        "https://hooks.slack.com/services/T0001/B0001/abc?x=1",
        "https://hooks.slack.com/services/../../x/y/z",
        "https://127.0.0.1/services/T0001/B0001/abc")) {
      mvc.perform(save("acme", writer, "{\"slackWebhook\":\"" + bad + "\"}")).andExpect(status().isBadRequest());
    }
    mvc.perform(save("acme", writer, "{\"emails\":[\"not an address\"]}")).andExpect(status().isBadRequest());
    StringBuilder many = new StringBuilder("[");
    for (int i = 0; i < 11; i++) many.append(i == 0 ? "" : ",").append("\"p").append(i).append("@acme.test\"");
    mvc.perform(save("acme", writer, "{\"emails\":" + many + "]}")).andExpect(status().isBadRequest());
    mvc.perform(save("acme", writer, "{\"thresholds\":[0]}")).andExpect(status().isBadRequest());
    mvc.perform(save("acme", writer, "{\"thresholds\":[]}")).andExpect(status().isBadRequest());
    mvc.perform(save("acme", writer, "{\"thresholds\":[1,2,3,4,5]}")).andExpect(status().isBadRequest());
    assertThat(alerts.find(5001)).isEmpty();
  }

  @Test
  void a_test_goes_to_every_channel_and_is_limited() throws Exception {
    mvc.perform(post("/api/orgs/acme/alerts/test").cookie(writer).header("Origin", SITE)).andExpect(status().isBadRequest());
    configure(true, List.of("ops@acme.test"), HOOK);
    mvc.perform(post("/api/orgs/acme/alerts/test").cookie(writer).header("Origin", SITE))
        .andExpect(status().isOk()).andExpect(jsonPath("$.emails").value(1)).andExpect(jsonPath("$.slackMessages").value(1));
    assertThat(outbox.emailsTo("ops@acme.test")).hasSize(1);
    assertThat(outbox.slack).hasSize(1);
    mvc.perform(post("/api/orgs/acme/alerts/test").cookie(writer).header("Origin", SITE)).andExpect(status().isOk());
    mvc.perform(post("/api/orgs/acme/alerts/test").cookie(writer).header("Origin", SITE)).andExpect(status().isOk());
    mvc.perform(post("/api/orgs/acme/alerts/test").cookie(writer).header("Origin", SITE)).andExpect(status().isTooManyRequests());
  }

  @Test
  void running_the_job_now_is_for_the_owner_only() throws Exception {
    mvc.perform(post("/api/alerts/run").cookie(writer).header("Origin", SITE)).andExpect(status().isForbidden());
    mvc.perform(post("/api/alerts/run").header("Authorization", "Bearer test-token")).andExpect(status().isOk());
  }

  // The daily job.

  @Test
  void thirty_days_out_each_channel_gets_one_digest_with_the_repositories_and_lines() {
    configure(true, List.of("ops@acme.test", "lead@acme.test"), HOOK);
    AlertJob.Summary s = job.run(DAY30);
    assertThat(s.teams().emails()).isEqualTo(2);
    assertThat(s.teams().slackMessages()).isEqualTo(1);

    JsonNode mail = outbox.emailsTo("ops@acme.test").get(0);
    assertThat(outbox.brevoKeys).containsOnly("brevo-test-key");
    assertThat(mail.path("sender").path("email").asString()).isEqualTo("alerts@vukisha.co.ke");
    assertThat(mail.has("htmlContent")).as("plain text only: nowhere for a tracking pixel").isFalse();
    assertThat(mail.path("subject").asString()).isEqualTo("acme: gpt-4-turbo shut down, in 30 days");
    String text = mail.path("textContent").asString();
    assertThat(text)
        .contains("In 30 days, 2026-10-23: OpenAI, gpt-4-turbo shut down (breaking)")
        .contains("acme/repo9001: openai:model:gpt-4-turbo")
        .contains("https://github.test/acme/repo9002/blob/sha9002/src/app.py#L3")
        .contains("How to migrate: https://developers.openai.com/api/docs/deprecations")
        .contains(SITE + "/#/app")
        .contains(SITE + "/unsubscribe?token=")
        .doesNotContain("globex");
    assertThat(mail.path("headers").path("List-Unsubscribe").asString()).startsWith("<" + SITE + "/unsubscribe?token=");
    assertThat(mail.path("headers").path("List-Unsubscribe-Post").asString()).isEqualTo("List-Unsubscribe=One-Click");

    assertThat(outbox.slackUrls).containsExactly(HOOK);
    assertThat(outbox.slack.get(0)).contains("*In 30 days* (2026-10-23) OpenAI, gpt-4-turbo shut down: acme/repo9001, acme/repo9002, acme/repo9003")
        .contains("<" + SITE + "/#/app|Open the dashboard>");
  }

  @Test
  void nothing_is_sent_twice_and_the_next_threshold_sends_again() {
    configure(true, List.of("ops@acme.test"), HOOK);
    job.run(DAY30);
    outbox.reset();
    assertThat(job.run(DAY30).teams()).isEqualTo(new TeamAlerts.Result(0, 0, 0));
    assertThat(job.run(DAY30.plusDays(1)).teams()).isEqualTo(new TeamAlerts.Result(0, 0, 0));
    assertThat(job.run(FakeScanEngine.EFFECTIVE.minusDays(8)).teams().emails()).isZero();

    TeamAlerts.Result week = job.run(FakeScanEngine.EFFECTIVE.minusDays(7)).teams();
    assertThat(week).isEqualTo(new TeamAlerts.Result(1, 1, 0));
    assertThat(outbox.emails.get(0).path("subject").asString()).endsWith("in 7 days");
    assertThat(job.run(FakeScanEngine.EFFECTIVE.minusDays(7)).teams()).isEqualTo(new TeamAlerts.Result(0, 0, 0));
    // After the date, nothing more.
    assertThat(job.run(FakeScanEngine.EFFECTIVE.plusDays(1)).teams()).isEqualTo(new TeamAlerts.Result(0, 0, 0));
  }

  @Test
  void a_missed_threshold_is_caught_up_once_not_twice() {
    configure(true, List.of("ops@acme.test"), null);
    // First run five days out: one warning, not a 30-day and a 7-day one together.
    assertThat(job.run(FakeScanEngine.EFFECTIVE.minusDays(5)).teams().emails()).isEqualTo(1);
    assertThat(outbox.emails.get(0).path("subject").asString()).endsWith("in 5 days");
    assertThat(job.run(FakeScanEngine.EFFECTIVE.minusDays(4)).teams().emails()).isZero();
  }

  @Test
  void a_failed_channel_is_tried_again_and_the_others_are_not_repeated() {
    configure(true, List.of("ops@acme.test"), HOOK);
    outbox.reset(HttpStatus.CREATED, HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(job.run(DAY30).teams()).isEqualTo(new TeamAlerts.Result(1, 0, 1));
    outbox.reset();
    assertThat(job.run(DAY30.plusDays(1)).teams()).isEqualTo(new TeamAlerts.Result(0, 1, 0));
    assertThat(outbox.emails).isEmpty();
  }

  @Test
  void snoozed_not_in_production_not_affected_and_fixed_findings_are_left_out() {
    configure(true, List.of("ops@acme.test"), null);
    findings.setStatus(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "snoozed", DAY30.plusDays(10));
    findings.setStatus(9002, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "not_in_prod", null);
    findings.setStatus(9003, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID, "not_affected", null);
    assertThat(job.run(DAY30).teams().emails()).isZero();
    // A snooze that has run out counts again.
    TeamAlerts.Result later = job.run(DAY30.plusDays(11)).teams();
    assertThat(later.emails()).isEqualTo(1);
    assertThat(outbox.emails.get(0).path("textContent").asString()).contains("acme/repo9001").doesNotContain("acme/repo9002").doesNotContain("acme/repo9003");
    findings.close(9001, FakeScanEngine.CONTRACT_ID, FakeScanEngine.CHANGE_ID);
    assertThat(job.run(FakeScanEngine.EFFECTIVE.minusDays(7)).teams().emails()).isZero();
  }

  @Test
  void alerts_switched_off_or_a_suspended_installation_send_nothing() {
    configure(false, List.of("ops@acme.test"), HOOK);
    assertThat(job.run(DAY30).teams()).isEqualTo(new TeamAlerts.Result(0, 0, 0));
    configure(true, List.of("ops@acme.test"), HOOK);
    installations.suspend(5001, true);
    assertThat(job.run(DAY30).teams()).isEqualTo(new TeamAlerts.Result(0, 0, 0));
  }

  @Test
  void a_recipient_can_take_their_own_address_off_the_list() throws Exception {
    configure(true, List.of("ops@acme.test", "lead@acme.test"), null);
    job.run(DAY30);
    String text = outbox.emailsTo("lead@acme.test").get(0).path("textContent").asString();
    Matcher m = Pattern.compile(Pattern.quote(SITE) + "(/unsubscribe\\?token=[A-Za-z0-9_.-]+)").matcher(text);
    assertThat(m.find()).isTrue();
    mvc.perform(get(m.group(1))).andExpect(status().isOk());
    assertThat(alerts.find(5001).orElseThrow().emails()).containsExactly("ops@acme.test");
    // A token for one organisation does nothing to another, and a changed token nothing at all.
    mvc.perform(get(m.group(1).replace("token=", "token=x"))).andExpect(status().isBadRequest());
  }
}
