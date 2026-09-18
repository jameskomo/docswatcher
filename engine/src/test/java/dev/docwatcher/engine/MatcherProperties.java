package dev.docwatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Property tests for the matcher over the real knowledge base. */
class MatcherProperties {

  private static final Knowledge K = TestSupport.knowledge();
  private static final List<String> SEVERITY_ORDER = List.of("breaking", "warning", "info");

  @Provide
  Arbitrary<Contract> contracts() {
    // Draw keys from the real change records so matches actually happen, plus some noise.
    List<String[]> pool = new ArrayList<>();
    for (Change c : K.changes()) for (Change.Affect a : c.affects()) pool.add(new String[] {c.provider(), a.kind(), a.match()});
    pool.add(new String[] {"openai", "model", "gpt-nothing"});
    pool.add(new String[] {"stripe", "endpoint", "GET /v1/nothing"});
    Arbitrary<String[]> triple = Arbitraries.of(pool);
    Arbitrary<String> confidence = Arbitraries.of("high", "medium", "low");
    Arbitrary<Integer> line = Arbitraries.integers().between(1, 500);
    return Combinators.combine(triple, confidence, line).as((t, conf, l) ->
        new Contract(Contract.id(t[0], t[1], t[2]), t[0], t[1], t[2], conf,
            List.of(new Evidence("src/a.py", l, 1, "x", "d", "literal")), null));
  }

  @Provide
  Arbitrary<LocalDate> dates() {
    return Arbitraries.integers().between(0, 1500).map(d -> LocalDate.of(2024, 1, 1).plusDays(d));
  }

  @Property
  void outputIsSortedByEffectiveThenSeverity(@ForAll("contracts") List<Contract> cs, @ForAll("dates") LocalDate today) {
    List<Finding> out = Matcher.match(MatcherTest.inventory(RepoRef.local("r"), cs), K, today, true);
    Comparator<Finding> order = Comparator
        .comparing((Finding f) -> f.effective() == null ? "9999-99-99" : f.effective())
        .thenComparingInt(f -> SEVERITY_ORDER.indexOf(f.severity()));
    for (int i = 1; i < out.size(); i++) assertThat(order.compare(out.get(i - 1), out.get(i))).isLessThanOrEqualTo(0);
  }

  @Property
  void lowConfidenceIsExcludedUnlessAsked(@ForAll("contracts") List<Contract> cs, @ForAll("dates") LocalDate today) {
    Inventory inv = MatcherTest.inventory(RepoRef.local("r"), cs);
    List<Finding> strict = Matcher.match(inv, K, today, false);
    List<Finding> loose = Matcher.match(inv, K, today, true);
    assertThat(strict).allMatch(f -> cs.stream().anyMatch(c -> c.id().equals(f.contract()) && !c.confidence().equals("low")));
    assertThat(loose.size()).isGreaterThanOrEqualTo(strict.size());
  }

  @Property
  void daysRemainingIsConsistentWithToday(@ForAll("contracts") List<Contract> cs, @ForAll("dates") LocalDate today) {
    for (Finding f : Matcher.match(MatcherTest.inventory(RepoRef.local("r"), cs), K, today, true)) {
      if (f.effective() == null) {
        assertThat(f.daysRemaining()).isNull();
      } else {
        LocalDate eff = LocalDate.parse(f.effective());
        assertThat(f.daysRemaining() < 0).isEqualTo(eff.isBefore(today));
        assertThat(today.plusDays(f.daysRemaining())).isEqualTo(eff);
      }
    }
  }

  @Property
  void onlyActiveAndExpiredChangesProduceFindings(@ForAll("contracts") List<Contract> cs, @ForAll("dates") LocalDate today) {
    for (Finding f : Matcher.match(MatcherTest.inventory(RepoRef.local("r"), cs), K, today, true)) {
      Change ch = K.changes().stream().filter(c -> c.id().equals(f.change())).findFirst().orElseThrow();
      assertThat(ch.status()).isIn("active", "expired");
      assertThat(f.status()).isEqualTo("open");
    }
  }

  @Property
  void isAPureFunction(@ForAll("contracts") List<Contract> cs, @ForAll("dates") LocalDate today) {
    Inventory inv = MatcherTest.inventory(RepoRef.local("r"), cs);
    assertThat(Json.write(Matcher.match(inv, K, today, true))).isEqualTo(Json.write(Matcher.match(inv, K, today, true)));
  }
}
