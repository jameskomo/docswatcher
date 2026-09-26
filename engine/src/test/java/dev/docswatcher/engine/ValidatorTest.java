package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ValidatorTest {

  @Test
  void theKnowledgeBaseIsValid() {
    // TODAY is pinned so fixture day counts never drift, but whether a record's status agrees with
    // its date depends on the real calendar: that is checked against the actual date by CI's
    // `docswatcher validate knowledge` step, and the rule itself by the lifecycle tests below.
    Validator.Report r = Validator.validate(TestSupport.knowledge(), TestSupport.TODAY);
    assertThat(r.errors()).filteredOn(e -> !e.contains(": status active but effective") && !e.contains(": status expired but effective"))
        .isEmpty();
  }

  private static Change record(String status, String effective) {
    return new Change("shopify-script-tag-create-update-rejected-2026", "shopify", "behavior_change", "breaking", "t", null,
        List.of(), "2026-01-01", effective, List.of(), null, status, "providers/shopify/changes/x.yaml");
  }

  private static Validator.Report lifecycle(String status, String effective, String today) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Validator.lifecycle(record(status, effective), LocalDate.parse(effective), LocalDate.parse(today), errors, warnings);
    return new Validator.Report(errors, warnings);
  }

  /** The week before an active record's date passes, the build says so, naming the record and the date. */
  @ParameterizedTest
  @CsvSource({"2026-09-24, in 7 days", "2026-09-30, in 1 day", "2026-10-01, today"})
  void warnsTheWeekBeforeAnActiveRecordMustBeExpired(String today, String when) {
    Validator.Report r = lifecycle("active", "2026-10-01", today);
    assertThat(r.errors()).isEmpty();
    assertThat(r.warnings()).singleElement().asString()
        .contains("shopify-script-tag-create-update-rejected-2026")
        .contains("2026-10-01 (" + when + ")")
        .contains("set status expired on 2026-10-02");
  }

  @Test
  void saysNothingEarlierThanAWeekAhead() {
    Validator.Report r = lifecycle("active", "2026-10-01", "2026-09-23");
    assertThat(r.errors()).isEmpty();
    assertThat(r.warnings()).isEmpty();
  }

  @Test
  void theDayAfterTheWarningBecomesTheError() {
    Validator.Report r = lifecycle("active", "2026-10-01", "2026-10-02");
    assertThat(r.warnings()).isEmpty();
    assertThat(r.errors()).singleElement().asString().contains("set status expired");
  }

  @Test
  void expiredRecordsAreNotWarnedAbout() {
    assertThat(lifecycle("expired", "2026-10-01", "2026-10-02").warnings()).isEmpty();
    assertThat(lifecycle("expired", "2026-10-01", "2026-09-28").errors()).singleElement().asString().contains("not in the past");
    assertThat(lifecycle("draft", "2026-10-01", "2026-09-28").warnings()).isEmpty();
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
