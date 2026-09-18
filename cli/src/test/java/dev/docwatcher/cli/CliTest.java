package dev.docwatcher.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CliTest {

  private static final Path KNOWLEDGE = Path.of("..", "knowledge").toAbsolutePath().normalize();

  record Run(int exit, String out) {}

  static Run run(String... args) {
    PrintStream original = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf, true));
    try {
      int exit = new CommandLine(new DocWatcher()).execute(args);
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
  void validatePasses() {
    Run r = run("validate", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(r.exit()).isZero();
    assertThat(r.out()).contains("OK · 0 errors");
  }
}
