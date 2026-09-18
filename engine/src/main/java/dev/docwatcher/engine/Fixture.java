package dev.docwatcher.engine;

import java.nio.file.Path;
import java.util.List;

/** A fixture: a tiny repo plus what the engines must produce for it. */
public record Fixture(
    String name,
    Path dir,
    Path repo,
    Path expectedInventory,
    Path expectedFindings,
    List<Finding.Pair> expectedFindingPairs) {

  public boolean isNegative() {
    return expectedFindingPairs.isEmpty();
  }
}
