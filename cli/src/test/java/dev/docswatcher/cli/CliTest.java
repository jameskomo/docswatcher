package dev.docswatcher.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

  private static final Path KNOWLEDGE = Path.of("..", "knowledge").toAbsolutePath().normalize();

  record Run(int exit, String out) {}

  static Run run(String... args) {
    PrintStream original = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf, true));
    try {
      int exit = DocsWatcher.commandLine().execute(args);
      return new Run(exit, buf.toString());
    } finally {
      System.setOut(original);
    }
  }

  @Test
  void scanPrintsInventoryJson() {
    Run r = run("scan", KNOWLEDGE.resolve("fixtures/openai-python-model-config/repo").toString(), "--knowledge", KNOWLEDGE.toString());
    assertThat(r.exit()).isZero();
    assertThat(r.out()).startsWith("{\n  \"schemaVersion\": \"1\"").contains("\"openai:model:gpt-4-turbo\"");
  }

  @Test
  void matchTextExitsOneOnBreakingFinding() {
    Run r = run("match", KNOWLEDGE.resolve("fixtures/openai-python-model-config/repo").toString(),
        "--knowledge", KNOWLEDGE.toString(), "--format", "text", "--today", "2026-09-18");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(r.out()).contains("External API drift").contains("Breaking · act before a date")
        .contains("gpt-4-turbo").contains("2026-10-23").contains("config/settings.yaml:2");
  }

  @Test
  void matchExitsZeroWhenNothingBreaking() {
    Run r = run("match", KNOWLEDGE.resolve("fixtures/stripe-negative-mock-in-tests/repo").toString(),
        "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(r.exit()).isZero();
    assertThat(r.out().trim()).isEqualTo("[]");
  }

  @Test
  void matchWritesJsonAndInventoryBesideTheTextFromOneScan(@TempDir Path out) throws Exception {
    String repo = KNOWLEDGE.resolve("fixtures/openai-python-model-config/repo").toString();
    Path report = out.resolve("findings.json");
    Path inventory = out.resolve("inventory.json");
    Run text = run("match", repo, "--knowledge", KNOWLEDGE.toString(), "--format", "text", "--today", "2026-09-18",
        "--report", report.toString(), "--inventory", inventory.toString());
    assertThat(text.exit()).isEqualTo(1);
    assertThat(text.out()).contains("External API drift");

    Run json = run("match", repo, "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(Files.readString(report)).isEqualTo(json.out());
    assertThat(Files.readString(inventory)).startsWith("{\n  \"schemaVersion\": \"1\"").contains("\"openai:model:gpt-4-turbo\"");
  }

  @Test
  void validatePasses() {
    Run r = run("validate", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(r.exit()).isZero();
    assertThat(r.out()).contains("OK · 0 errors");
  }

  @Test
  void theBundledAndTheDirectoryKnowledgeReportTheSameVersionFile() throws java.io.IOException {
    String version = java.nio.file.Files.readString(KNOWLEDGE.resolve("VERSION")).trim();
    // No directory: the working directory (cli/) has no knowledge/, so this is the copy the jar carries.
    Run bundled = run("validate", "--today", "2026-09-18");
    Run directory = run("validate", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(bundled.out()).startsWith("Knowledge " + version + ": ");
    assertThat(directory.out()).startsWith("Knowledge " + version + ": ");
  }

  @Test
  void matchHonoursDocswatcherignoreAndExclude() {
    String repo = KNOWLEDGE.resolve("fixtures/openai-python-docswatcherignore/repo").toString();
    Run withIgnoreFile = run("match", repo, "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-23");
    assertThat(withIgnoreFile.exit()).isEqualTo(1);
    assertThat(withIgnoreFile.out()).contains("gpt-4-turbo").doesNotContain("dall-e-2");

    Run excluded = run("match", repo, "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-23", "--exclude", "src/");
    assertThat(excluded.exit()).isZero();
    assertThat(excluded.out().trim()).isEqualTo("[]");
  }

  /** A scan cut short with nothing breaking in what it read is not clean: it exits 4 and says why. */
  @Test
  void anIncompleteScanWithNothingBreakingExitsFourAndSaysSo() {
    PrintStream original = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true));
    Run r;
    try {
      r = run("match", KNOWLEDGE.resolve("fixtures/stripe-negative-mock-in-tests/repo").toString(),
          "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18", "--max-files", "1");
    } finally {
      System.setErr(original);
    }
    assertThat(r.exit()).isEqualTo(DocsWatcher.INCOMPLETE);
    assertThat(r.out().trim()).isEqualTo("[]");
    assertThat(err.toString()).contains("warning: Scan incomplete: stopped at the 1-file limit, 2 files not scanned").contains("--max-files");
  }

  @Test
  void anIncompleteScanWithABreakingFindingStillExitsOneAndTheTextSaysItIsIncomplete() {
    Run r = run("match", KNOWLEDGE.resolve("fixtures/openai-python-model-config/repo").toString(),
        "--knowledge", KNOWLEDGE.toString(), "--format", "text", "--today", "2026-09-18", "--max-files", "1");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(r.out()).contains("Scanned 1 files").contains("Scan incomplete: stopped at the 1-file limit, 2 files not scanned");
  }

  @Test
  void scanRecordsTheStopInTheInventoryAndExitsFour() {
    Run r = run("scan", KNOWLEDGE.resolve("fixtures/openai-python-model-config/repo").toString(), "--knowledge", KNOWLEDGE.toString(),
        "--max-total-mb", "1", "--max-files", "2");
    assertThat(r.exit()).isEqualTo(DocsWatcher.INCOMPLETE);
    assertThat(r.out()).contains("\"incomplete\": {").contains("\"limit\": \"maxFiles\"").contains("\"filesNotScanned\": 1");
  }

  @Test
  void aScanThatFailsExitsThreeNotOne() {
    // 1 means "a breaking finding is open". A failure must never be mistaken for that, or for its
    // opposite once the empty output is counted as zero findings.
    Run r = run("match", KNOWLEDGE.resolve("does-not-exist").toString(), "--knowledge", KNOWLEDGE.toString());
    assertThat(r.exit()).isEqualTo(DocsWatcher.FAILED);
    assertThat(r.out()).isEmpty();
  }
}
