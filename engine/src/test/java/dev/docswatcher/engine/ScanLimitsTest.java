package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A scan a whole-scan limit stopped keeps what it found and says it is incomplete. */
class ScanLimitsTest {

  private static final Duration LONG = Duration.ofMinutes(10);

  /** Five files: a.py .. e.py, 100 bytes each, and one ignored file that never counts. */
  private static Path repo(Path dir) throws IOException {
    for (String name : List.of("a", "b", "c", "d", "e")) {
      Files.writeString(dir.resolve(name + ".py"), "x".repeat(99) + "\n");
    }
    Files.writeString(dir.resolve(".gitignore"), "ignored.py\n");
    Files.writeString(dir.resolve("ignored.py"), "y\n");
    return dir;
  }

  private static Inventory scan(Path dir, ScanLimits limits) {
    return new Engine(TestSupport.knowledge()).scan(dir, RepoRef.local("limits"), List.of(), limits);
  }

  @Test
  void aScanWithinTheLimitsSaysNothing(@TempDir Path dir) throws IOException {
    Inventory inv = scan(repo(dir), ScanLimits.DEFAULT);
    assertThat(inv.stats().incomplete()).isNull();
    assertThat(Json.write(inv.stats())).doesNotContain("incomplete");
  }

  @Test
  void stopsAtTheFileLimitAndCountsWhatWasNotRead(@TempDir Path dir) throws IOException {
    // .gitignore is read too: it is a file like any other, so 3 files are a.py, b.py and .gitignore.
    Inventory inv = scan(repo(dir), new ScanLimits(3, Long.MAX_VALUE, LONG));
    assertThat(inv.stats().filesScanned()).isEqualTo(3);
    assertThat(inv.stats().incomplete()).isEqualTo(new Inventory.Incomplete(ScanLimits.MAX_FILES, 3, 3));
    assertThat(inv.stats().filesSkipped()).isZero();
    assertThat(Json.write(inv.stats())).contains("\"incomplete\": {\n    \"limit\": \"maxFiles\",\n    \"max\": 3,\n    \"filesNotScanned\": 3\n  }");
  }

  @Test
  void stopsBeforeTheFileThatWouldPassTheByteLimit(@TempDir Path dir) throws IOException {
    FileTree tree = FileTree.read(repo(dir), List.of(), new ScanLimits(1000, 250, LONG), () -> false);
    // .gitignore (11 bytes) and a.py, b.py (100 each) fit; c.py would make 311.
    assertThat(tree.files).extracting(f -> f.path).containsExactly(".gitignore", "a.py", "b.py");
    assertThat(tree.incomplete).isEqualTo(new Inventory.Incomplete(ScanLimits.MAX_BYTES, 250, 3));
  }

  @Test
  void stopsReadingWhenTimeRunsOut(@TempDir Path dir) throws IOException {
    FileTree tree = FileTree.read(repo(dir), List.of(), new ScanLimits(1000, Long.MAX_VALUE, Duration.ofSeconds(7)), () -> true);
    assertThat(tree.files).isEmpty();
    assertThat(tree.incomplete).isEqualTo(new Inventory.Incomplete(ScanLimits.MAX_DURATION, 7000, 6));
  }

  @Test
  void describesTheStopForPeople() {
    assertThat(new Inventory.Incomplete(ScanLimits.MAX_BYTES, 200L * 1024 * 1024, 1).describe())
        .isEqualTo("Scan incomplete: stopped at the 200 MB limit, 1 file not scanned. Nothing in them is reported. "
            + "Exclude paths that need no scan, or raise the limit.");
    assertThat(new Inventory.Incomplete(ScanLimits.MAX_DURATION, 600_000, 12).describe()).contains("600-second limit, 12 files");
  }

  @Test
  void refusesLimitsThatAreNotPositive() {
    assertThatThrownBy(() -> new ScanLimits(0, 1, LONG)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ScanLimits(1, 1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anIncompleteInventoryReadsBack(@TempDir Path dir) throws IOException {
    Inventory inv = scan(repo(dir), new ScanLimits(2, Long.MAX_VALUE, LONG));
    assertThat(Json.read(Json.write(inv), Inventory.class).stats().incomplete()).isEqualTo(inv.stats().incomplete());
  }
}
