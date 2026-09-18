package dev.docwatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Nightly: a synthetic 2000-file tree must scan inside the budget. Run with -DexcludedGroups= to include. */
@Tag("nightly")
class PerformanceTest {

  @Test
  void twoThousandFilesUnderBudget(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("requirements.txt"), "openai==1.0\nstripe==10\n");
    for (int i = 0; i < 2000; i++) {
      Path d = dir.resolve("pkg" + (i % 50));
      Files.createDirectories(d);
      String body = "import os\n\n\ndef f" + i + "():\n    return client.chat.completions.create(model=\"gpt-4-turbo\")\n"
          + "# ".repeat(10) + "\n".repeat(20);
      Files.writeString(d.resolve("m" + i + ".py"), body);
    }
    Knowledge k = TestSupport.knowledge();
    Engine e = new Engine(k);
    e.scan(dir, RepoRef.local("warm"));
    long start = System.nanoTime();
    Inventory inv = e.scan(dir, RepoRef.local("perf"));
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertThat(inv.stats().filesScanned()).isEqualTo(2001);
    assertThat(inv.contracts()).extracting(Contract::id).contains("openai:model:gpt-4-turbo", "openai:endpoint:POST /v1/chat/completions");
    assertThat(ms).as("scan time ms").isLessThan(10_000);
  }
}
