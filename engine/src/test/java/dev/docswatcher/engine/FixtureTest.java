package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Every fixture: the scan must equal expected-inventory.json byte for byte, and the match must equal expected-findings.json. */
class FixtureTest {

  private final Knowledge k = TestSupport.knowledge();

  @TestFactory
  Stream<DynamicTest> inventories() {
    return k.fixtures().stream().map(f -> DynamicTest.dynamicTest("inventory " + f.name(), () -> {
      Inventory inv = new Engine(k).scan(f.repo(), RepoRef.local(f.name()));
      String expected = Files.readString(f.expectedInventory());
      assertThat(Json.write(inv.contracts())).isEqualTo(expected);
    }));
  }

  @TestFactory
  Stream<DynamicTest> findings() {
    return k.fixtures().stream().map(f -> DynamicTest.dynamicTest("findings " + f.name(), () -> {
      Inventory inv = new Engine(k).scan(f.repo(), RepoRef.local(f.name()));
      List<Finding.Pair> pairs = Matcher.match(inv, k, TestSupport.TODAY, false).stream().map(Finding::pair).toList();
      assertThat(Json.write(pairs)).isEqualTo(Files.readString(f.expectedFindings()));
    }));
  }

  @TestFactory
  Stream<DynamicTest> negativesHaveNoFindings() {
    return k.fixtures().stream().filter(f -> f.name().contains("-negative-")).map(f ->
        DynamicTest.dynamicTest("negative " + f.name(), () -> assertThat(f.expectedFindingPairs()).isEmpty()));
  }
}
