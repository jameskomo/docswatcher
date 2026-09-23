package dev.docswatcher.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.docswatcher.engine.Finding;
import dev.docswatcher.engine.Inventory;
import dev.docswatcher.engine.Json;
import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.Matcher;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** The test plan in docs/14-coding-agents.md, one test per row. */
class McpServerTest {

  private static final Path KNOWLEDGE = Path.of("..", "knowledge").toAbsolutePath().normalize();
  private static final Knowledge K = Knowledge.load(KNOWLEDGE);
  private static final LocalDate TODAY = LocalDate.parse("2026-09-23");
  private static final McpServer SERVER = new McpServer(K, () -> TODAY, KNOWLEDGE, "test");

  private static int ids;

  static JsonNode call(String method, String params) {
    String msg = "{\"jsonrpc\":\"2.0\",\"id\":" + (++ids) + ",\"method\":\"" + method + "\""
        + (params == null ? "" : ",\"params\":" + params) + "}";
    return SERVER.handle(msg);
  }

  static JsonNode tool(String name, String args) {
    JsonNode r = call("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}");
    assertThat(r.has("error")).as("tool calls answer with a result, not a protocol error: %s", r).isFalse();
    return r.get("result");
  }

  static String text(JsonNode result) {
    return result.get("content").get(0).get("text").asText();
  }

  // ---- Connecting

  @Test
  void initializeAnswersWithVersionCapabilityAndInstructions() {
    JsonNode r = call("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
    JsonNode res = r.get("result");
    assertThat(res.get("protocolVersion").asText()).isEqualTo("2025-06-18");
    assertThat(res.path("capabilities").has("tools")).isTrue();
    assertThat(res.path("serverInfo").path("name").asText()).isEqualTo("docswatcher");
    assertThat(res.get("instructions").asText()).contains("check_api").contains("OpenAI");
  }

  @Test
  void unknownProtocolVersionGetsOurLatest() {
    JsonNode r = call("initialize", "{\"protocolVersion\":\"1999-01-01\"}");
    assertThat(r.get("result").get("protocolVersion").asText()).isEqualTo(McpServer.PROTOCOL_VERSIONS.get(0));
  }

  @Test
  void toolsListHasThreeToolsWithObjectSchemas() {
    JsonNode tools = call("tools/list", null).get("result").get("tools");
    List<String> names = new ArrayList<>();
    tools.forEach(t -> {
      names.add(t.get("name").asText());
      assertThat(t.get("inputSchema").get("type").asText()).isEqualTo("object");
      assertThat(t.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
    });
    assertThat(names).containsExactly("check_api", "upcoming_deprecations", "scan_repository");
  }

  // ---- check_api

  @Test
  void retiredModelSaysRetiredWithReplacement() {
    JsonNode r = tool("check_api", "{\"value\":\"dall-e-2\"}");
    assertThat(r.get("isError").asBoolean()).isFalse();
    assertThat(r.get("structuredContent").get("verdict").asText()).isEqualTo("RETIRED");
    assertThat(text(r)).startsWith("RETIRED 134 days ago (2026-05-12)").contains("Replacement: gpt-image-2");
  }

  @Test
  void futureShutdownSaysRetiringWithDaysLeft() {
    JsonNode r = tool("check_api", "{\"value\":\"gpt-4-turbo\"}");
    JsonNode match = r.get("structuredContent").get("matches").get(0);
    assertThat(r.get("structuredContent").get("verdict").asText()).isEqualTo("RETIRING");
    assertThat(match.get("daysRemaining").asInt()).isEqualTo(30);
    assertThat(match.get("replacement").asText()).isEqualTo("gpt-5.6-sol");
    assertThat(text(r)).startsWith("RETIRING in 30 days (2026-10-23)");
  }

  @Test
  void looseInputFindsTheSameChangeAsTheScannerWould() {
    String direct = firstMatchId(tool("check_api", "{\"value\":\"gpt-4-turbo\"}"));
    assertThat(firstMatchId(tool("check_api", "{\"value\":\"openai/gpt-4-turbo\"}"))).isEqualTo(direct);
    assertThat(firstMatchId(tool("check_api", "{\"value\":\"GPT-4-Turbo\"}"))).isEqualTo(direct);

    String assistants = "openai-assistants-api-shutdown-2026";
    assertThat(firstMatchId(tool("check_api", "{\"value\":\"POST /v1/assistants\"}"))).isEqualTo(assistants);
    assertThat(firstMatchId(tool("check_api", "{\"value\":\"/v1/assistants\"}"))).isEqualTo(assistants);
    JsonNode url = tool("check_api", "{\"value\":\"https://api.openai.com/v1/assistants\"}");
    assertThat(firstMatchId(url)).isEqualTo(assistants);
    assertThat(url.get("structuredContent").get("query").get("interpretedAs").get(0).get("provider").asText()).isEqualTo("openai");
  }

  @Test
  void unknownValueSaysSoAndNamesWhatKnownMeans() {
    JsonNode r = tool("check_api", "{\"value\":\"gpt-9-imaginary\"}");
    assertThat(r.get("isError").asBoolean()).isFalse();
    assertThat(r.get("structuredContent").get("verdict").asText()).isEqualTo("NO_KNOWN_DEPRECATION");
    assertThat(text(r)).startsWith("NO KNOWN DEPRECATION").contains(K.changes().size() + " deprecation records")
        .contains("not that nothing is");
    // Claude Code shows the model structuredContent, not the text, so the caveat must be there too.
    assertThat(r.get("structuredContent").get("answer").asText()).isEqualTo(text(r));
  }

  @Test
  void aVersionNumberIsNotComparedAgainstDatedApiVersions() {
    // api_version rules compare strings, and "0.28" sorts below "<2024-04". Only a date-shaped value
    // may be tried as an API version, or this would report a Shopify shutdown for a pip pin.
    JsonNode r = tool("check_api", "{\"value\":\"0.28\"}");
    assertThat(r.get("structuredContent").get("verdict").asText()).isEqualTo("NO_KNOWN_DEPRECATION");
  }

  @Test
  void badArgumentsAreAToolErrorNotAProtocolError() {
    assertThat(tool("check_api", "{}").get("isError").asBoolean()).isTrue();
    assertThat(tool("check_api", "{\"value\":\"x\",\"provider\":\"nope\"}").get("isError").asBoolean()).isTrue();
    JsonNode unknownTool = call("tools/call", "{\"name\":\"nope\",\"arguments\":{}}");
    assertThat(unknownTool.get("error").get("code").asInt()).isEqualTo(-32602);
  }

  // ---- upcoming_deprecations

  @Test
  void upcomingRespectsProviderAndWindowAndIsSorted() {
    JsonNode r = tool("upcoming_deprecations", "{\"provider\":\"openai\",\"within_days\":31}");
    JsonNode list = r.get("structuredContent").get("deprecations");
    assertThat(list.size()).isGreaterThan(0);
    String previous = "";
    for (JsonNode d : list) {
      assertThat(d.get("provider").asText()).isEqualTo("openai");
      assertThat(d.get("daysRemaining").asInt()).isBetween(0, 31);
      assertThat(d.get("effective").asText()).isGreaterThanOrEqualTo(previous);
      previous = d.get("effective").asText();
    }
    assertThat(text(r)).contains("2026-10-23  in 30 days  OpenAI: gpt-4-turbo shut down -> gpt-5.6-sol");
  }

  @Test
  void upcomingRejectsAnImpossibleWindow() {
    assertThat(tool("upcoming_deprecations", "{\"within_days\":0}").get("isError").asBoolean()).isTrue();
  }

  // ---- scan_repository

  @Test
  void scanGivesTheSameFindingsAsMatch() {
    Path repo = KNOWLEDGE.resolve("fixtures/openai-python-model-config/repo");
    Inventory inv = ScanCommand.scan(K, repo, null, null, null);
    List<String> expected = Matcher.match(inv, K, TODAY, false).stream().map(Finding::change).toList();

    JsonNode r = tool("scan_repository", "{\"path\":\"fixtures/openai-python-model-config/repo\"}");
    List<String> got = new ArrayList<>();
    r.get("structuredContent").get("findings").forEach(f -> got.add(f.get("change").asText()));
    assertThat(got).isNotEmpty().isEqualTo(expected);
    assertThat(text(r)).contains("External API drift");
  }

  @Test
  void scanOfAMissingPathIsAToolError() {
    JsonNode r = tool("scan_repository", "{\"path\":\"does/not/exist\"}");
    assertThat(r.get("isError").asBoolean()).isTrue();
    assertThat(text(r)).startsWith("Not a directory");
  }

  // ---- Protocol hygiene

  @Test
  void unknownMethodAndMalformedJsonGetStandardErrors() {
    assertThat(call("resources/list", null).get("error").get("code").asInt()).isEqualTo(-32601);
    assertThat(SERVER.handle("{not json").get("error").get("code").asInt()).isEqualTo(-32700);
    assertThat(SERVER.handle("[1,2]").get("error").get("code").asInt()).isEqualTo(-32600);
  }

  @Test
  void notificationsGetNoReply() {
    assertThat(SERVER.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
    assertThat(SERVER.handle("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}")).isNull();
  }

  @Test
  void serveWritesOnlyJsonRpcLinesAndOneReplyPerRequest() throws Exception {
    String session = String.join("\n",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}",
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
        "",
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"check_api\",\"arguments\":{\"value\":\"gpt-4-turbo\"}}}",
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}") + "\n";
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    SERVER.serve(new BufferedReader(new StringReader(session)), new PrintStream(buf, true, StandardCharsets.UTF_8));

    String[] lines = buf.toString(StandardCharsets.UTF_8).split("\n");
    assertThat(lines).hasSize(3);
    int expectedId = 1;
    for (String line : lines) {
      JsonNode n = Json.tree(line);
      assertThat(n.get("jsonrpc").asText()).isEqualTo("2.0");
      assertThat(n.get("id").asInt()).isEqualTo(expectedId++);
    }
  }

  @Test
  void theMcpSubcommandKeepsStdoutForTheProtocol() {
    InputStream originalIn = System.in;
    PrintStream originalOut = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      System.setIn(new ByteArrayInputStream(
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8)));
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
      int exit = new CommandLine(new DocsWatcher()).execute("mcp", "--knowledge", KNOWLEDGE.toString());
      assertThat(exit).isZero();
    } finally {
      System.setIn(originalIn);
      System.setOut(originalOut);
    }
    // The ready message goes to stderr; stdout holds exactly one JSON-RPC reply.
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n");
  }

  private static String firstMatchId(JsonNode result) {
    JsonNode matches = result.get("structuredContent").get("matches");
    assertThat(matches.size()).as("expected a match in %s", result).isGreaterThan(0);
    return matches.get(0).get("id").asText();
  }
}
