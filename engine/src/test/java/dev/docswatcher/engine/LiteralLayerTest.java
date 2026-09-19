package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LiteralLayerTest {

  private final Knowledge k = TestSupport.knowledge();

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "config.yaml | model: gpt-4-turbo | openai:model:gpt-4-turbo",
      "config.yaml | model: gpt-4o-2024-05-13 | openai:model:gpt-4o-2024-05-13",
      "config.yaml | model: gpt-4.1-nano | openai:model:gpt-4.1-nano",
      "config.yaml | model: gpt-image-1 | openai:model:gpt-image-1",
      "a.py | m = \"o1-2024-12-17\" | openai:model:o1-2024-12-17",
      "a.py | m = \"o3-mini\" | openai:model:o3-mini",
      "a.py | x = \"claude-3-haiku-20240307\" | anthropic:model:claude-3-haiku-20240307",
      "a.py | x = \"claude-opus-4-1\" | anthropic:model:claude-opus-4-1",
      "a.py | x = \"claude-2.1\" | anthropic:model:claude-2.1",
      "a.js | fetch(`https://x/admin/api/2025-10/orders.json`) | shopify:api_version:2025-10",
      "a.rb | api_version: \"2026-01\" | shopify:api_version:2026-01",
      "a.py | stripe.api_version = \"2024-06-20\" | stripe:api_version:2024-06-20",
      "a.py | url = \"https://api.stripe.com/v1/sources\" | stripe:endpoint:ANY /v1/sources",
      "a.py | url = \"https://api.openai.com/v1/chat/completions\" | openai:endpoint:ANY /v1/chat/completions",
  })
  void detects(String path, String line, String expectedId) {
    List<Contract> out = TestSupport.scan(k, TestSupport.file(path, line + "\n"));
    assertThat(out).extracting(Contract::id).contains(expectedId);
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "a.py | uses claude-code-action in CI",
      "a.py | o1 = compute()",
      "a.py | x = 'o12'",
      "a.py | version = 2025-10",
      "a.py | name = \"gptx\"",
      "a.py | name = \"egpt-4\"",
  })
  void doesNotFire(String path, String line) {
    List<Contract> out = TestSupport.scan(k, TestSupport.file(path, line + "\n"));
    assertThat(out).isEmpty();
  }

  @Test
  void columnIsGroupOneStartAndLinesAreOneBased() {
    List<Contract> out = TestSupport.scan(k, TestSupport.file("c.yaml", "a: 1\n  model:  gpt-4-turbo\n"));
    Evidence e = out.get(0).evidence().get(0);
    assertThat(e.line()).isEqualTo(2);
    assertThat(e.column()).isEqualTo(11);
    assertThat(e.snippet()).isEqualTo("model:  gpt-4-turbo");
  }

  @Test
  void nonAsciiBeforeMatchStillCountsChars() {
    List<Contract> out = TestSupport.scan(k, TestSupport.file("c.yaml", "note: héllo → gpt-4-turbo\n"));
    Evidence e = out.get(0).evidence().get(0);
    assertThat(e.column()).isEqualTo(15);
  }

  @Test
  void docAndTestPathsDowngradeToLow() {
    List<Contract> a = TestSupport.scan(k, TestSupport.file("README.md", "gpt-4-turbo\n"));
    List<Contract> b = TestSupport.scan(k, TestSupport.file("tests/a.py", "m = 'gpt-4-turbo'\n"));
    List<Contract> c = TestSupport.scan(k, TestSupport.file("README.md", "gpt-4-turbo\n"), TestSupport.file("src/a.py", "m = 'gpt-4-turbo'\n"));
    assertThat(a.get(0).confidence()).isEqualTo("low");
    assertThat(b.get(0).confidence()).isEqualTo("low");
    assertThat(c.get(0).confidence()).isEqualTo("medium");
  }

  @Test
  void sameKeyAcrossFilesMergesEvidenceInPathOrder() {
    List<Contract> out = TestSupport.scan(k,
        TestSupport.file("z.py", "m = 'gpt-4-turbo'\n"),
        TestSupport.file("a.py", "m = 'gpt-4-turbo'\nn = 'gpt-4-turbo'\n"));
    assertThat(out).hasSize(1);
    assertThat(out.get(0).evidence()).extracting(Evidence::path).containsExactly("a.py", "a.py", "z.py");
    assertThat(out.get(0).evidence()).extracting(Evidence::line).containsExactly(1, 2, 1);
  }
}
