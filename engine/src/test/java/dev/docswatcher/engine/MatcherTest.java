package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MatcherTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "endpoint | POST /v1/sources | POST /v1/sources | true",
      "endpoint | POST /v1/sources | ANY /v1/sources | true",
      "endpoint | ANY /v1/sources | GET /v1/sources | true",
      "endpoint | POST /v1/sources | GET /v1/sources | false",
      "endpoint | POST /v1/charges/* | POST /v1/charges/ch_1 | true",
      "endpoint | POST /v1/charges/* | POST /v1/charges/ch_1/capture | false",
      "api_version | < 2022-11-15 | 2020-08-27 | true",
      "api_version | < 2022-11-15 | 2022-11-15 | false",
      "api_version | <= 2022-11-15 | 2022-11-15 | true",
      "api_version | 2025-10 | 2025-10 | true",
      "model | gpt-4-turbo | gpt-4-turbo-2024-04-09 | false",
      "sdk_method | Source.create | Source.create | true",
  })
  void matchSemantics(String kind, String pattern, String key, boolean expected) {
    assertThat(Matcher.matches(kind, pattern, key)).isEqualTo(expected);
  }

  @Test
  void findingIdAndDaysRemaining() {
    Knowledge k = TestSupport.knowledge();
    Contract c = new Contract("openai:model:gpt-4-turbo", "openai", "model", "gpt-4-turbo", "medium",
        List.of(new Evidence("a.py", 1, 1, "x", "d", "literal")), null);
    Inventory inv = inventory(new RepoRef("github", "acme", "shop", null, null), List.of(c));
    List<Finding> out = Matcher.match(inv, k, LocalDate.of(2026, 9, 18), false);
    assertThat(out).singleElement().satisfies(f -> {
      assertThat(f.id()).isEqualTo("acme/shop:openai-gpt-4-turbo-shutdown-2026:openai:model:gpt-4-turbo");
      assertThat(f.effective()).isEqualTo("2026-10-23");
      assertThat(f.daysRemaining()).isEqualTo(35);
      assertThat(f.status()).isEqualTo("open");
      assertThat(f.severity()).isEqualTo("breaking");
    });
  }

  @Test
  void expiredChangesStillMatchWithNegativeDays() {
    Knowledge k = TestSupport.knowledge();
    Contract c = new Contract("anthropic:model:claude-3-haiku-20240307", "anthropic", "model", "claude-3-haiku-20240307", "medium",
        List.of(new Evidence("a.py", 1, 1, "x", "d", "literal")), null);
    List<Finding> out = Matcher.match(inventory(RepoRef.local("x"), List.of(c)), k, LocalDate.of(2026, 9, 18), false);
    assertThat(out).singleElement().satisfies(f -> assertThat(f.daysRemaining()).isNegative());
  }

  static Inventory inventory(RepoRef repo, List<Contract> contracts) {
    return new Inventory("1", repo, "2026-09-18T00:00:00Z", new Inventory.EngineInfo("t", "0", "0"),
        new Inventory.Stats(1, 0, 0, List.of()), contracts);
  }
}
