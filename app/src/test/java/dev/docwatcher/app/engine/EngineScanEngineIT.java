package dev.docwatcher.app.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.docwatcher.app.config.AppProperties;
import dev.docwatcher.app.model.InventoryDoc;
import dev.docwatcher.app.model.RepoRefDoc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises the real seam between the app and the engine library. Every other test in this
 * module uses the fake engine, so without this one the adapter's JSON round trip never runs.
 * It is held to the same fixtures as the engine and the browser engine.
 */
@EnabledIf("knowledgePresent")
class EngineScanEngineIT {

  private static final Path KNOWLEDGE = Path.of("..", "knowledge");
  private static EngineScanEngine subject;

  static boolean knowledgePresent() {
    return Files.isDirectory(KNOWLEDGE.resolve("providers"));
  }

  @BeforeAll
  static void start() {
    var properties =
        new AppProperties(
            new AppProperties.GitHub("1", "", "", "https://api.github.com", "docwatcher:fix", "docwatcher:snooze", "docwatcher:not-in-prod"),
            new AppProperties.Api("t", false),
            new AppProperties.Web("*"),
            new AppProperties.Knowledge(KNOWLEDGE.toString()),
            new AppProperties.Worker(false, 1, 1000, 60));
    subject = new EngineScanEngine(properties, new ObjectMapper());
  }

  private static InventoryDoc scanFixture(String name) {
    return subject.scan(
        KNOWLEDGE.resolve("fixtures").resolve(name).resolve("repo"),
        new RepoRefDoc("fixture", "docwatcher", name, "fixture", "0000000"));
  }

  @Test
  void loads_the_knowledge_base_through_the_adapter() {
    assertThat(subject.knowledgeVersion()).isNotBlank();
    assertThat(subject.changes()).hasSizeGreaterThan(40);
    assertThat(subject.change("stripe-sources-api-deprecated")).isPresent();
    assertThat(subject.change("stripe-sources-api-deprecated").orElseThrow().migration().guide())
        .contains("stripe.com");
  }

  @Test
  void scan_of_a_fixture_matches_the_expected_inventory_exactly() throws Exception {
    var fixture = KNOWLEDGE.resolve("fixtures/stripe-java-sources");
    InventoryDoc inventory = scanFixture("stripe-java-sources");

    // expected-inventory.json holds the bare contracts array, per docs/02-schemas.md.
    var mapper = new ObjectMapper();
    var actual = mapper.valueToTree(inventory.contracts());
    var expected = mapper.readTree(Files.readString(fixture.resolve("expected-inventory.json")));

    assertThat(actual.toString())
        .as("contracts crossing the adapter must survive the JSON round trip")
        .isEqualTo(expected.toString());
  }

  @Test
  void findings_survive_the_round_trip_and_carry_evidence() {
    var findings = subject.match(scanFixture("stripe-java-sources"));

    assertThat(findings).hasSize(2);
    assertThat(findings)
        .allSatisfy(
            f -> {
              assertThat(f.change()).isEqualTo("stripe-sources-api-deprecated");
              assertThat(f.severity()).isEqualTo("warning");
              assertThat(f.evidence()).isNotEmpty();
              assertThat(f.evidence().getFirst().path()).isEqualTo("src/main/java/pay/SourceClient.java");
              assertThat(f.evidence().getFirst().line()).isPositive();
            });
    assertThat(findings.stream().map(f -> f.contract()).toList())
        .containsExactlyInAnyOrder("stripe:endpoint:POST /v1/sources", "stripe:sdk_method:Source.create");
  }

  @Test
  void a_negative_fixture_produces_no_findings_through_the_adapter() {
    InventoryDoc inventory = scanFixture("anthropic-negative-readme-only");

    assertThat(subject.match(inventory)).as("low confidence contracts must not raise findings").isEmpty();
    assertThat(inventory.contracts()).isNotEmpty();
    assertThat(inventory.contracts()).allSatisfy(c -> assertThat(c.confidence()).isEqualTo("low"));
  }

  @Test
  void every_fixture_scans_and_matches_without_throwing() throws Exception {
    List<Path> repos;
    try (var s = Files.list(KNOWLEDGE.resolve("fixtures"))) {
      repos = s.filter(p -> Files.isDirectory(p.resolve("repo"))).sorted().toList();
    }
    assertThat(repos).hasSizeGreaterThan(5);
    for (Path fixture : repos) {
      var inv = scanFixture(fixture.getFileName().toString());
      assertThat(inv.schemaVersion()).isEqualTo("1");
      assertThat(inv.stats()).isNotNull();
      subject.match(inv);
    }
  }
}
