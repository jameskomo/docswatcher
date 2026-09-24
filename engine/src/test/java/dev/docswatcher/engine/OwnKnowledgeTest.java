package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** docs/19-your-own-apis.md. The TypeScript engine runs the same cases in web/tests/unit/own.test.ts. */
class OwnKnowledgeTest {

  private static final Path CASES = Path.of("src/test/resources/own-knowledge-cases.json");
  private static final Knowledge BASE = TestSupport.knowledge();

  @TestFactory
  Stream<DynamicTest> sharedCases(@TempDir Path tmp) throws IOException {
    JsonNode root = Json.tree(Files.readString(CASES));
    LocalDate today = LocalDate.parse(root.get("today").asText());
    List<DynamicTest> tests = new ArrayList<>();
    int n = 0;
    for (JsonNode c : root.get("cases")) {
      Path repo = tmp.resolve("case" + n++);
      tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
        var files = c.get("files").fields();
        while (files.hasNext()) {
          var e = files.next();
          write(repo, e.getKey(), e.getValue().asText());
        }
        List<OwnKnowledge.Source> shared = new ArrayList<>();
        for (JsonNode s : c.path("shared")) shared.add(new OwnKnowledge.Source(s.asText(), repo.resolve(s.asText())));
        OwnKnowledge.Result r = OwnKnowledge.merge(BASE, OwnKnowledge.sources(repo, shared), today);
        assertThat(r.errors()).as("errors").containsExactlyElementsOf(strings(c.get("errors")));
        assertThat(r.warnings()).as("warnings").containsExactlyElementsOf(strings(c.get("warnings")));
        assertThat(r.providers().stream().map(Provider::id).sorted().toList()).as("providers").isEqualTo(strings(c.get("providers")));
        assertThat(r.changes().stream().map(Change::id).sorted().toList()).as("changes").isEqualTo(strings(c.get("changes")));
        if (!r.ok() || r.providers().isEmpty()) assertThat(r.knowledge()).isSameAs(BASE);
      }));
    }
    return tests.stream();
  }

  @Test
  void validRecordsAreScannedAndMatchedAndTheirDirectoryIsNotScannedAsCode(@TempDir Path repo) throws IOException {
    write(repo, ".docswatcher/providers/internal-billing/provider.yaml", "id: internal-billing\nname: Billing\n");
    write(repo, ".docswatcher/providers/internal-billing/detectors.yaml", """
        literals:
          - id: internal-billing.literal.endpoint
            kind: endpoint
            files: ["**/*"]
            pattern: 'billing\\.acme\\.dev(/v[0-9]+/[a-z]+)'
            key: "ANY $1"
        """);
    write(repo, ".docswatcher/providers/internal-billing/changes/internal-billing-v1.yaml", """
        id: internal-billing-v1
        provider: internal-billing
        kind: sunset
        severity: breaking
        title: v1 off
        affects: [{kind: endpoint, match: "ANY /v1/invoices"}]
        announced: 2026-09-01
        effective: 2027-01-31
        sources: [{kind: changelog, url: "https://billing.acme.dev/v1/invoices", observed: 2026-09-01}]
        status: active
        """);
    write(repo, "app.py", "requests.get('https://billing.acme.dev/v1/invoices')\n");

    OwnKnowledge.Result r = OwnKnowledge.merge(BASE, OwnKnowledge.sources(repo, List.of()), TestSupport.TODAY);
    assertThat(r.errors()).isEmpty();
    assertThat(r.summary()).isEqualTo("Your own API records: 1 provider, 1 change record");
    assertThat(r.knowledge().version()).isEqualTo(BASE.version());
    assertThat(r.knowledge().providers()).hasSize(BASE.providers().size() + 1);

    Inventory inv = new Engine(r.knowledge()).scan(repo, RepoRef.local("billing"));
    // The change record's source URL names the endpoint too, but records are knowledge, not code.
    assertThat(inv.contracts()).singleElement().satisfies(c -> {
      assertThat(c.id()).isEqualTo("internal-billing:endpoint:ANY /v1/invoices");
      assertThat(c.evidence()).extracting(Evidence::path).containsExactly("app.py");
    });
    assertThat(Matcher.match(inv, r.knowledge(), TestSupport.TODAY, false))
        .extracting(Finding::change).containsExactly("internal-billing-v1");
    assertThat(Matcher.match(inv, BASE, TestSupport.TODAY, false)).isEmpty();
  }

  @Test
  void invalidRecordsLeaveTheBaseUnchanged(@TempDir Path repo) throws IOException {
    write(repo, ".docswatcher/providers/orders/provider.yaml", "id: orders\nname: Orders\n");
    OwnKnowledge.Result r = OwnKnowledge.merge(BASE, OwnKnowledge.sources(repo, List.of()), TestSupport.TODAY);
    assertThat(r.ok()).isFalse();
    assertThat(r.knowledge()).isSameAs(BASE);
    assertThat(r.summary()).isEqualTo("Your own API records were not used: 1 error");
  }

  @Test
  void aSharedDirectoryThatIsTheRepositorysOwnIsReadOnce(@TempDir Path repo) throws IOException {
    write(repo, ".docswatcher/providers/internal-a/provider.yaml", "id: internal-a\nname: A\n");
    List<OwnKnowledge.Source> sources = OwnKnowledge.sources(repo,
        List.of(new OwnKnowledge.Source("./.docswatcher", repo.resolve(".docswatcher/../.docswatcher"))));
    assertThat(sources).extracting(OwnKnowledge.Source::label).containsExactly(".docswatcher");
    assertThat(OwnKnowledge.merge(BASE, sources, TestSupport.TODAY).errors()).isEmpty();
  }

  @Test
  void aMissingSharedDirectoryIsAnError(@TempDir Path repo) {
    OwnKnowledge.Result r = OwnKnowledge.merge(BASE,
        OwnKnowledge.sources(repo, List.of(new OwnKnowledge.Source("../org-records", repo.resolve("nope")))), TestSupport.TODAY);
    assertThat(r.errors()).containsExactly("../org-records: no providers/ directory");
  }

  @Test
  void invalidYamlIsReportedWithItsFile(@TempDir Path repo) throws IOException {
    write(repo, ".docswatcher/providers/internal-a/provider.yaml", "id: [unclosed\n");
    OwnKnowledge.Result r = OwnKnowledge.merge(BASE, OwnKnowledge.sources(repo, List.of()), TestSupport.TODAY);
    assertThat(r.errors()).singleElement().asString().startsWith(".docswatcher/providers/internal-a/provider.yaml: not valid YAML: ");
  }

  @Test
  void aBundledProviderMayNotUseTheInternalPrefix() {
    Provider p = new Provider("internal-x", "X", null, null, null, List.of(), List.of(), null,
        new Provider.Detectors(List.of(new Provider.Manifest("npm", "x")), List.of(), List.of()));
    Knowledge k = BASE.with(List.of(p), List.of());
    assertThat(Validator.validate(k, TestSupport.TODAY).errors())
        .contains("provider id internal-x is reserved: ids starting internal- belong to a team's own records");
  }

  @Test
  void anOwnPatternThatRunsPastItsBudgetStopsTheScanWithItsName() {
    String text = "a".repeat(5_000) + "!";
    CharSequence budgeted = new LiteralLayer.Budgeted(text, "internal-x.literal.slow", "src/slow.txt", System.nanoTime() - 1);
    assertThatThrownBy(() -> java.util.regex.Pattern.compile("(a+)+$").matcher(budgeted).find())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("internal-x.literal.slow")
        .hasMessageContaining("src/slow.txt");
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(x -> out.add(x.asText()));
    return out;
  }

  private static void write(Path root, String rel, String text) throws IOException {
    Path p = root.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, text);
  }
}
