package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

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
}
