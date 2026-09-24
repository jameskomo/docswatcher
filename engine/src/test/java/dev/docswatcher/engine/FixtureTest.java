package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every fixture: the scan must equal expected-inventory.json byte for byte, and the match must equal
 * expected-findings.json. A fixture whose repo/ has its own .docswatcher/ records is scanned and
 * matched with them, as every surface does.
 */
class FixtureTest {

  private final Knowledge k = TestSupport.knowledge();

  private Knowledge knowledgeFor(Fixture f) {
    OwnKnowledge.Result own = OwnKnowledge.merge(k, OwnKnowledge.sources(f.repo(), List.of()), TestSupport.TODAY);
    assertThat(own.errors()).as("own records of fixture " + f.name()).isEmpty();
    return own.knowledge();
  }

  @TestFactory
  Stream<DynamicTest> inventories() {
    return k.fixtures().stream().map(f -> DynamicTest.dynamicTest("inventory " + f.name(), () -> {
      Inventory inv = new Engine(knowledgeFor(f)).scan(f.repo(), RepoRef.local(f.name()));
      String expected = Files.readString(f.expectedInventory());
      assertThat(Json.write(inv.contracts())).isEqualTo(expected);
    }));
  }

  @TestFactory
  Stream<DynamicTest> findings() {
    return k.fixtures().stream().map(f -> DynamicTest.dynamicTest("findings " + f.name(), () -> {
      Knowledge fk = knowledgeFor(f);
      Inventory inv = new Engine(fk).scan(f.repo(), RepoRef.local(f.name()));
      List<Finding.Pair> pairs = Matcher.match(inv, fk, TestSupport.TODAY, false).stream().map(Finding::pair).toList();
      assertThat(Json.write(pairs)).isEqualTo(Files.readString(f.expectedFindings()));
    }));
  }

  @TestFactory
  Stream<DynamicTest> negativesHaveNoFindings() {
    return k.fixtures().stream().filter(f -> f.name().contains("-negative-")).map(f ->
        DynamicTest.dynamicTest("negative " + f.name(), () -> assertThat(f.expectedFindingPairs()).isEmpty()));
  }
}
