package dev.docswatcher.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.docswatcher.app.model.ProviderDoc;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderAttributorTest {

  private final ProviderAttributor subject = new ProviderAttributor(List.of(
      new ProviderDoc("openai", "OpenAI", List.of("https://api.openai.com")),
      new ProviderDoc("slack", "Slack", List.of("https://slack.com/api")),
      new ProviderDoc("shopify", "Shopify", List.of("https://{shop}.myshopify.com/admin/api")),
      new ProviderDoc("aws", "AWS SDK", List.of("https://amazonaws.com")),
      new ProviderDoc("twilio", "Twilio", List.of("https://api.twilio.com", "https://notify.twilio.com"))));

  private static HttpCall call(String host, String method, String path) {
    return new HttpCall(host, method, path, null, null);
  }

  @Test
  void matches_an_exact_host() {
    assertThat(subject.provider(call("api.openai.com", "POST", "/v1/assistants"))).contains("openai");
  }

  @Test
  void matches_a_subdomain_of_a_bare_apex() {
    assertThat(subject.provider(call("s3.eu-central-1.amazonaws.com", "GET", "/bucket/key"))).contains("aws");
  }

  @Test
  void a_templated_host_matches_any_shop() {
    assertThat(subject.provider(call("acme-store.myshopify.com", "GET", "/admin/api/2024-10/products.json"))).contains("shopify");
  }

  @Test
  void a_base_url_with_a_path_does_not_claim_the_whole_host() {
    assertThat(subject.provider(call("slack.com", "POST", "/api/rtm.connect"))).contains("slack");
    assertThat(subject.provider(call("slack.com", "GET", "/marketing/page"))).isEmpty();
  }

  @Test
  void an_unknown_host_attributes_to_nothing() {
    assertThat(subject.provider(call("metrics.internal.acme.test", "GET", "/healthz"))).isEmpty();
  }

  @Test
  void candidates_cover_both_the_method_and_the_any_spelling() {
    assertThat(subject.candidateContractIds("openai", call("api.openai.com", "POST", "/v1/assistants")))
        .contains("openai:endpoint:POST /v1/assistants", "openai:endpoint:ANY /v1/assistants");
  }

  @Test
  void candidates_include_the_subdomain_form_that_twilio_keys_use() {
    assertThat(subject.candidateContractIds("twilio", call("notify.twilio.com", "GET", "/v1/Credentials")))
        .contains("twilio:endpoint:ANY /notify/v1/Credentials");
  }
}
