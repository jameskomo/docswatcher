package dev.docswatcher.app.earlyaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.docswatcher.app.support.PostgresTest;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** docs/adr/0005-early-access-requests.md, against a real database. */
@AutoConfigureMockMvc
class EarlyAccessIT extends PostgresTest {

  /** Each test gets its own client address, so the rate limit of one never touches another. */
  private static final AtomicInteger CLIENTS = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired EarlyAccessStore store;

  private String client;

  @BeforeEach
  void clean() {
    jdbc.sql("delete from early_access").update();
    client = "203.0.113." + CLIENTS.incrementAndGet();
  }

  private ResultActions submit(String json) throws Exception {
    return mvc.perform(post("/early-access").header("CF-Connecting-IP", client)
        .contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private int rows() {
    return jdbc.sql("select count(*) from early_access").query(Integer.class).single();
  }

  @Test
  void storesARequestWithoutAToken() throws Exception {
    submit("""
        {"email":"Lead@Example.com","company":"Acme","repositories":"10-50","providers":"OpenAI, Stripe",
         "interest":"alerts","message":"We ship weekly.\\nTwo teams."}
        """).andExpect(status().isCreated()).andExpect(jsonPath("$.ok").value(true));
    EarlyAccessStore.Request r = store.all().get(0);
    assertThat(r.email()).isEqualTo("lead@example.com");
    assertThat(r.company()).isEqualTo("Acme");
    assertThat(r.message()).isEqualTo("We ship weekly.\nTwo teams.");
  }

  @Test
  void aRepeatUpdatesTheSameRow() throws Exception {
    submit("{\"email\":\"a@b.co\",\"company\":\"First\"}").andExpect(status().isCreated());
    submit("{\"email\":\"a@b.co\",\"company\":\"Second\"}").andExpect(status().isCreated());
    assertThat(rows()).isEqualTo(1);
    assertThat(store.all().get(0).company()).isEqualTo("Second");
    assertThat(store.all().get(0).requests()).isEqualTo(2);
  }

  @Test
  void rejectsAnInvalidEmailAndStoresNothing() throws Exception {
    submit("{\"email\":\"not an email\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
    submit("{\"company\":\"no email at all\"}").andExpect(status().isBadRequest());
    assertThat(rows()).isZero();
  }

  @Test
  void aFilledHoneypotIsAcknowledgedAndDropped() throws Exception {
    submit("{\"email\":\"bot@spam.example\",\"website\":\"http://spam.example\"}").andExpect(status().isCreated());
    assertThat(rows()).isZero();
  }

  @Test
  void cleansAndLimitsFields() throws Exception {
    String longName = "x".repeat(500);
    submit("{\"email\":\"c@d.co\",\"company\":\"" + longName + "\",\"interest\":\"a\\u0007b\"}").andExpect(status().isCreated());
    EarlyAccessStore.Request r = store.all().get(0);
    assertThat(r.company()).hasSize(EarlyAccessController.MAX_FIELD);
    assertThat(r.interest()).isEqualTo("a b");
  }

  @Test
  void limitsRequestsPerClient() throws Exception {
    for (int i = 0; i < EarlyAccessController.PER_WINDOW; i++) {
      submit("{\"email\":\"r" + i + "@e.co\"}").andExpect(status().isCreated());
    }
    submit("{\"email\":\"over@e.co\"}").andExpect(status().isTooManyRequests());
    assertThat(rows()).isEqualTo(EarlyAccessController.PER_WINDOW);
  }

  @Test
  void onlyTheOwnerCanReadRequests() throws Exception {
    submit("{\"email\":\"e@f.co\"}").andExpect(status().isCreated());
    mvc.perform(get("/api/early-access")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/early-access").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].email").value("e@f.co"));
  }
}
