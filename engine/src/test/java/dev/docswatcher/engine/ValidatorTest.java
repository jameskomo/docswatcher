package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ValidatorTest {

  @Test
  void theKnowledgeBaseIsValid() {
    Validator.Report r = Validator.validate(TestSupport.knowledge(), TestSupport.TODAY);
    assertThat(r.errors()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "a*+b | possessive",
      "\\h | Java-only",
      "(?i)abc | inline flags",
      "(?<=x)y | lookbehind",
      "\\p{Lu} | only",
      "\\Aabc | Java-only",
      "[unclosed | does not compile",
  })
  void rejectsJavaOnlyRegex(String pattern, String reason) {
    assertThat(RegexDialect.problems(pattern)).anyMatch(p -> p.contains(reason));
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "\\b(gpt-[0-9][0-9a-z.-]*)\\b",
      "(?:a|b)+c?",
      "(?<name>x)",
      "x(?=y)",
      "[\"']([0-9]{4}-[0-9]{2})[\"']",
  })
  void acceptsCommonSubset(String pattern) {
    assertThat(RegexDialect.problems(pattern)).isEmpty();
  }
}
