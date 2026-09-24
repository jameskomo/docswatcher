package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

class CallsiteLayerTest {

  private final Knowledge k = TestSupport.knowledge();

  @Test
  void pythonChatCompletionsMapsToEndpoint() {
    List<Contract> out = TestSupport.scan(k,
        TestSupport.file("requirements.txt", "openai\n"),
        TestSupport.file("app.py", "from openai import OpenAI\nclient = OpenAI()\nr = client.chat.completions.create(model=\"x\")\n"));
    assertThat(out).extracting(Contract::id).containsExactly(
        "openai:endpoint:POST /v1/chat/completions",
        "openai:sdk_method:chat.completions.create",
        "openai:sdk_package:openai");
    Evidence e = out.get(0).evidence().get(0);
    assertThat(e.line()).isEqualTo(3);
    assertThat(e.column()).isEqualTo(5);
    assertThat(e.layer()).isEqualTo("callsite");
    assertThat(e.detector()).isEqualTo("openai.python.chat-completions-create");
  }

  @Test
  void callsitesNeedTheManifest() {
    List<Contract> out = TestSupport.scan(k,
        TestSupport.file("app.py", "r = client.chat.completions.create(model=\"x\")\n"));
    assertThat(out).isEmpty();
  }

  @Test
  void callsitesNeedTheSpecificPackageTheRuleRequires() {
    List<Contract> out = TestSupport.scan(k,
        TestSupport.file("package.json", "{\"dependencies\":{\"stripe\":\"1\"}}"),
        TestSupport.file("A.java", "class A { void m() { Source.create(p); } }\n"));
    assertThat(out).extracting(Contract::id).containsExactly("stripe:sdk_package:stripe");
  }

  @Test
  void typescriptTsxAndJavascriptAllRun() {
    String pkg = "{\"dependencies\":{\"@anthropic-ai/sdk\":\"1\"}}";
    String body = "const r = await client.messages.create({});\n";
    for (String file : List.of("a.ts", "a.tsx", "a.js", "a.mjs")) {
      List<Contract> out = TestSupport.scan(k, TestSupport.file("package.json", pkg), TestSupport.file(file, body));
      assertThat(out).as(file).extracting(Contract::id).contains("anthropic:sdk_method:messages.create", "anthropic:endpoint:POST /v1/messages");
    }
  }

  @Test
  void javaSourceCreate() {
    List<Contract> out = TestSupport.scan(k,
        TestSupport.file("pom.xml", "<project><dependencies><dependency><groupId>com.stripe</groupId><artifactId>stripe-java</artifactId></dependency></dependencies></project>"),
        TestSupport.file("A.java", "class A {\n  Object m() { return Source.create(p); }\n}\n"));
    assertThat(out).extracting(Contract::id).contains("stripe:sdk_method:Source.create", "stripe:endpoint:POST /v1/sources");
    assertThat(out.stream().filter(c -> c.kind().equals("sdk_method")).findFirst().get().evidence().get(0).column()).isEqualTo(23);
  }

  @Test
  void predicatesRejectOtherMethods() {
    List<Contract> out = TestSupport.scan(k,
        TestSupport.file("requirements.txt", "openai\n"),
        TestSupport.file("app.py", "r = client.chat.completions.list()\n"));
    assertThat(out).extracting(Contract::id).containsExactly("openai:sdk_package:openai");
  }

  @Test
  void aFileIsParsedOnlyWhenItHoldsEveryEqStringOfSomePattern() {
    Provider openai = k.providers().stream().filter(p -> p.id().equals("openai")).findFirst().orElseThrow();
    Provider.Callsite rule = openai.detectors().callsites().stream()
        .filter(c -> c.id().equals("openai.python.chat-completions-create")).findFirst().orElseThrow();
    CallsiteLayer.Compiled c = CallsiteLayer.Compiled.of(new CallsiteLayer.Rule(openai, rule), new Grammars().get("python"));
    assertThat(c.needs()).containsExactly(List.of("chat", "completions", "create"));
    assertThat(c.mayMatch("client.chat.completions.create()")).isTrue();
    assertThat(c.mayMatch("client.chat.completions.list()")).isFalse();

    // A pattern without #eq? needs nothing, and a second pattern is a second way in.
    Provider.Callsite loose = new Provider.Callsite(rule.id(), rule.language(), rule.kind(), rule.requires(),
        "((call) @call) ((identifier) @a (#eq? @a \"never\"))", rule.key(), rule.mapsTo(), rule.confidence());
    CallsiteLayer.Compiled l = CallsiteLayer.Compiled.of(new CallsiteLayer.Rule(openai, loose), new Grammars().get("python"));
    assertThat(l.needs()).containsExactly(List.of(), List.of("never"));
    assertThat(l.mayMatch("x = 1")).isTrue();
  }

  @Test
  void everyQueryInTheKnowledgeBaseCompiles() {
    assertThat(CallsiteLayer.compileAll(k.providers())).isEmpty();
  }

  @Test
  void columnsCountCharactersAfterMultiByteText() {
    List<Contract> out = TestSupport.scan(k,
        TestSupport.file("requirements.txt", "openai\n"),
        TestSupport.file("app.py", "s = \"😀é\"; r = client.chat.completions.create(model=\"x\")\n"));
    Evidence e = out.stream().filter(c -> c.kind().equals("sdk_method")).findFirst().get().evidence().get(0);
    assertThat(e.line()).isEqualTo(1);
    assertThat(e.column()).isEqualTo(16);
  }

  @Test
  void aFileOutOfTimeIsLeftOutOfTheCallsiteLayerOnly() {
    Accumulator acc = new Accumulator();
    List<SourceFile> files = List.of(
        TestSupport.file("requirements.txt", "openai\n"),
        TestSupport.file("app.py", "r = client.chat.completions.create(model=\"x\")\n".repeat(2000)));
    ManifestLayer.run(k.providers(), files, acc);
    new CallsiteLayer(Duration.ZERO).run(k.providers(), files, acc);
    assertThat(acc.finish()).extracting(Contract::id).containsExactly("openai:sdk_package:openai");
  }

  @Test
  void treesWithLongRunsOfPunctuationAreNotQueried() {
    int run = 2 * CallsiteLayer.MAX_ANONYMOUS_RUN + 2;
    assertThat(queryable("javascript", "(".repeat(run) + "x")).isFalse();
    assertThat(queryable("javascript", "x = [" + ",".repeat(run) + "];")).isFalse();
    assertThat(queryable("javascript", "(".repeat(CallsiteLayer.MAX_ANONYMOUS_RUN - 10) + "x")).isTrue();
    assertThat(queryable("javascript", "f(" + "a.b(".repeat(2000) + ")".repeat(2001) + ";\n" + "x;\n".repeat(5000))).isTrue();
    assertThat(queryable("python", "x = [" + "1, ".repeat(5000) + "]\n")).isTrue();
  }

  @Test
  void veryDeepTreesAreNotQueried() {
    int n = CallsiteLayer.MAX_DEPTH;
    assertThat(queryable("javascript", "a.b(".repeat(n) + ")".repeat(n) + ";")).isFalse();
    assertThat(queryable("javascript", "a.b(".repeat(100) + ")".repeat(100) + ";")).isTrue();
  }

  private static boolean queryable(String language, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    try (TSParser parser = new TSParser()) {
      parser.setLanguage(new Grammars().get(language));
      CallsiteLayer.BoundedInput input = new CallsiteLayer.BoundedInput(bytes, System.nanoTime() + Duration.ofSeconds(30).toNanos());
      try (TSTree tree = parser.parseWithOptions(new byte[4096], null, input, TSInputEncoding.TSInputEncodingUTF8, input)) {
        return CallsiteLayer.queryable(tree.getRootNode(), input);
      }
    }
  }

  /** A cancelled parse resumes on the next call unless the parser is reset. */
  @Test
  void aCancelledParseLeavesTheParserUsableAfterReset() {
    byte[] chunk = new byte[4096];
    try (TSParser parser = new TSParser()) {
      parser.setLanguage(new TreeSitterPython());
      byte[] big = "x = f(1)\n".repeat(50_000).getBytes(StandardCharsets.UTF_8);
      CallsiteLayer.BoundedInput expired = new CallsiteLayer.BoundedInput(big, System.nanoTime());
      assertThat(parser.parseWithOptions(chunk, null, expired, TSInputEncoding.TSInputEncodingUTF8, expired)).isNull();
      parser.reset();

      byte[] small = "y = 2\n".getBytes(StandardCharsets.UTF_8);
      CallsiteLayer.BoundedInput fresh = new CallsiteLayer.BoundedInput(small, System.nanoTime() + Duration.ofSeconds(10).toNanos());
      try (TSTree tree = parser.parseWithOptions(chunk, null, fresh, TSInputEncoding.TSInputEncodingUTF8, fresh)) {
        assertThat(tree).isNotNull();
        assertThat(tree.getRootNode().getEndByte()).isEqualTo(small.length);
      }
    }
  }
}
