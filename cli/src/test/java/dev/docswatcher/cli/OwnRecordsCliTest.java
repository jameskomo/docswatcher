package dev.docswatcher.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.OwnKnowledge;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** docs/19-your-own-apis.md from the command line: scan, match, validate and the MCP server. */
class OwnRecordsCliTest {

  private static final Path KNOWLEDGE = Path.of("..", "knowledge").toAbsolutePath().normalize();
  private static final Path FIXTURE = KNOWLEDGE.resolve("fixtures/internal-orders-own-records/repo");
  private static final Path RECORDS = FIXTURE.resolve(".docswatcher");

  record Run(int exit, String out, String err) {}

  static Run run(String... args) {
    PrintStream original = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    StringWriter picocliErr = new StringWriter();
    System.setOut(new PrintStream(buf, true));
    System.setErr(new PrintStream(errBuf, true));
    try {
      CommandLine cl = DocsWatcher.commandLine();
      cl.setErr(new PrintWriter(picocliErr, true));
      int exit = cl.execute(args);
      return new Run(exit, buf.toString(), errBuf + picocliErr.toString());
    } finally {
      System.setOut(original);
      System.setErr(originalErr);
    }
  }

  @Test
  void matchReadsTheRepositorysOwnRecords() {
    Run r = run("match", FIXTURE.toString(), "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18", "--format", "text");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(r.out()).contains("Orders service · sdk method `OrdersClient.createOrder` · sunset 2027-03-31")
        .contains("Orders API v1 is switched off")
        .contains("src/checkout.ts:6");
  }

  @Test
  void aSharedDirectoryAddsRecordsToAnyRepository(@TempDir Path tmp) throws IOException {
    Path consumer = tmp.resolve("consumer");
    copy(FIXTURE.resolve("src/checkout.ts"), consumer.resolve("src/checkout.ts"));
    copy(FIXTURE.resolve("package.json"), consumer.resolve("package.json"));

    Run without = run("match", consumer.toString(), "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(without.exit()).isZero();
    assertThat(without.out().trim()).isEqualTo("[]");

    Run with = run("match", consumer.toString(), "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18",
        "--knowledge-extra", RECORDS.toString());
    assertThat(with.exit()).isEqualTo(1);
    assertThat(with.out()).contains("internal-orders-v1-sunset-2027");
  }

  @Test
  void invalidOwnRecordsStopTheScanAndNameEveryError(@TempDir Path repo) throws IOException {
    write(repo, ".docswatcher/providers/orders/provider.yaml", "id: orders\n");
    write(repo, "app.py", "print('x')\n");
    Run r = run("match", repo.toString(), "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(r.exit()).isEqualTo(DocsWatcher.FAILED);
    assertThat(r.out()).isEmpty();
    assertThat(r.err()).contains("your own API records have 2 errors, so nothing was scanned")
        .contains("provider id orders must start with internal- and name your service")
        .contains("orders: name missing");
  }

  @Test
  void validateChecksADocswatcherDirectoryAgainstTheKnowledgeBase(@TempDir Path repo) throws IOException {
    Run ok = run("validate", RECORDS.toString(), "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(ok.exit()).isZero();
    assertThat(ok.out()).contains("Your own API records from " + RECORDS + ": 1 provider, 2 change records")
        .contains("OK · 0 errors, 0 warnings");

    Run later = run("validate", "--knowledge-extra", RECORDS.toString(), "--knowledge", KNOWLEDGE.toString(), "--today", "2027-06-01");
    assertThat(later.exit()).as("a passed date is a warning, never a failed build").isZero();
    assertThat(later.out()).contains("warning: " + RECORDS + "/providers/internal-orders/changes/internal-orders-v1-sunset-2027.yaml: "
        + "status active but effective 2027-03-31 is in the past; set status expired");

    write(repo, ".docswatcher/providers/internal-x/provider.yaml", "id: internal-x\nname: X\n");
    write(repo, ".docswatcher/providers/internal-x/changes/x.yaml", "id: internal-x-1\nprovider: stripe\n");
    Run bad = run("validate", repo.resolve(".docswatcher").toString(), "--knowledge", KNOWLEDGE.toString(), "--today", "2026-09-18");
    assertThat(bad.exit()).isEqualTo(1);
    assertThat(bad.out()).contains("provider stripe is bundled").contains("FAILED · ");
  }

  @Test
  void theMcpServerAnswersForTheRecordsOfTheDirectoryItStartedIn() {
    Knowledge k = Knowledge.load(KNOWLEDGE);
    McpServer server = new McpServer(k, List.of(), () -> LocalDate.parse("2026-09-23"), FIXTURE, "test");
    JsonNode r = server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"check_api\","
        + "\"arguments\":{\"value\":\"https://orders.internal.acme.dev/v1/orders\"}}}").get("result");
    assertThat(r.get("content").get(0).get("text").asText())
        .startsWith("RETIRING in 189 days (2027-03-31): Orders API v1 is switched off [Orders service].");
    assertThat(r.path("structuredContent").path("knowledgeBase").path("ownRecords").path("providers").asInt()).isEqualTo(1);

    JsonNode sdk = server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"check_api\","
        + "\"arguments\":{\"value\":\"OrdersClient.createOrder\"}}}").get("result");
    assertThat(sdk.path("structuredContent").path("verdict").asText()).isEqualTo("RETIRING");
  }

  @Test
  void theMcpServerSaysSoInEveryAnswerWhenItsRecordsAreInvalid(@TempDir Path root) throws IOException {
    write(root, ".docswatcher/providers/orders/provider.yaml", "id: orders\nname: Orders\n");
    McpServer server = new McpServer(Knowledge.load(KNOWLEDGE), List.of(), () -> LocalDate.parse("2026-09-23"), root, "test");
    assertThat(server.own().ok()).isFalse();
    JsonNode r = server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"check_api\","
        + "\"arguments\":{\"value\":\"gpt-9-imaginary\"}}}").get("result");
    assertThat(r.get("content").get(0).get("text").asText())
        .contains("Note: your own API records were not loaded: 1 error, the first being \"provider id orders must start with internal-");
    JsonNode scan = server.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"scan_repository\","
        + "\"arguments\":{\"path\":\".\"}}}").get("result");
    assertThat(scan.get("isError").asBoolean()).isTrue();
    assertThat(scan.get("content").get(0).get("text").asText()).startsWith("Nothing was scanned");
  }

  @Test
  void theMcpServerScansAnotherDirectoryWithThatDirectorysRecords() {
    McpServer server = new McpServer(Knowledge.load(KNOWLEDGE), List.of(), () -> LocalDate.parse("2026-09-23"), KNOWLEDGE, "test");
    JsonNode r = server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"scan_repository\","
        + "\"arguments\":{\"path\":\"fixtures/internal-orders-own-records/repo\"}}}").get("result");
    assertThat(r.path("structuredContent").path("findingCount").asInt()).isEqualTo(3);
    assertThat(OwnKnowledge.inRepo(KNOWLEDGE)).isEmpty();
  }

  private static void copy(Path from, Path to) throws IOException {
    Files.createDirectories(to.getParent());
    Files.copy(from, to);
  }

  private static void write(Path root, String rel, String text) throws IOException {
    Path p = root.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, text);
  }
}
