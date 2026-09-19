package dev.docswatcher.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SpanReaderTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private Optional<HttpCall> read(String json) {
    return SpanReader.read(mapper.readTree(json));
  }

  @Test
  void reads_the_current_semantic_conventions() {
    var call = read("""
        {"kind":3,"attributes":[
          {"key":"http.request.method","value":{"stringValue":"post"}},
          {"key":"server.address","value":{"stringValue":"api.openai.com"}},
          {"key":"url.path","value":{"stringValue":"/v1/assistants"}}]}
        """).orElseThrow();
    assertThat(call.host()).isEqualTo("api.openai.com");
    assertThat(call.method()).isEqualTo("POST");
    assertThat(call.path()).isEqualTo("/v1/assistants");
  }

  @Test
  void reads_the_older_spellings_too() {
    var call = read("""
        {"kind":"SPAN_KIND_CLIENT","attributes":[
          {"key":"http.method","value":{"stringValue":"GET"}},
          {"key":"net.peer.name","value":{"stringValue":"api.stripe.com"}},
          {"key":"http.target","value":{"stringValue":"/v1/sources"}}]}
        """).orElseThrow();
    assertThat(call.host()).isEqualTo("api.stripe.com");
    assertThat(call.path()).isEqualTo("/v1/sources");
  }

  @Test
  void a_full_url_fills_in_a_missing_host_and_path_and_loses_the_query_string() {
    var call = read("""
        {"kind":3,"attributes":[
          {"key":"http.request.method","value":{"stringValue":"GET"}},
          {"key":"url.full","value":{"stringValue":"https://api.openai.com:443/v1/models?after=abc&limit=5"}}]}
        """).orElseThrow();
    assertThat(call.host()).isEqualTo("api.openai.com");
    assertThat(call.path()).isEqualTo("/v1/models");
  }

  @Test
  void header_arrays_are_kept_whole() {
    var call = read("""
        {"kind":3,"attributes":[
          {"key":"http.request.method","value":{"stringValue":"GET"}},
          {"key":"server.address","value":{"stringValue":"api.openai.com"}},
          {"key":"url.path","value":{"stringValue":"/v1/assistants"}},
          {"key":"http.response.header.sunset","value":{"arrayValue":{"values":[
            {"stringValue":"Wed, 26 Aug 2026 00:00:00 GMT"},{"stringValue":"Thu, 27 Aug 2026 00:00:00 GMT"}]}}}]}
        """).orElseThrow();
    assertThat(call.sunset()).isEqualTo("Wed, 26 Aug 2026 00:00:00 GMT, Thu, 27 Aug 2026 00:00:00 GMT");
    assertThat(call.hasProviderNotice()).isTrue();
  }

  @Test
  void a_server_span_is_not_an_outbound_call() {
    assertThat(read("""
        {"kind":2,"attributes":[
          {"key":"http.request.method","value":{"stringValue":"GET"}},
          {"key":"server.address","value":{"stringValue":"api.openai.com"}}]}
        """)).isEmpty();
  }

  @Test
  void a_span_with_no_host_is_unusable() {
    assertThat(read("""
        {"kind":3,"attributes":[{"key":"http.request.method","value":{"stringValue":"GET"}}]}
        """)).isEmpty();
  }

  @Test
  void a_malformed_attribute_list_does_not_throw() {
    assertThat(read("{\"kind\":3,\"attributes\":\"not-an-array\"}")).isEmpty();
  }

  @Test
  void a_missing_method_becomes_any_rather_than_being_dropped() {
    var call = read("""
        {"kind":3,"attributes":[
          {"key":"server.address","value":{"stringValue":"api.openai.com"}},
          {"key":"url.path","value":{"stringValue":"/v1/assistants/"}}]}
        """).orElseThrow();
    assertThat(call.method()).isEqualTo("ANY");
    assertThat(call.path()).isEqualTo("/v1/assistants");
  }
}
