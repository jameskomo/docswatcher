package dev.docswatcher.app.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.store.StoredFinding;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingReconcilerTest {

  private static FindingDoc derived(String contract, String change) {
    return new FindingDoc("id", contract, change, "breaking", LocalDate.of(2026, 10, 23), 10, List.of(), "open", null, null);
  }

  private static StoredFinding stored(String contract, String change) {
    return new StoredFinding(1, contract, change, "id", "breaking", null, "open", null, 7, null, null, null);
  }

  @Test
  void opensNewKeepsExistingClosesGone() {
    var plan = FindingReconciler.plan(
        List.of(derived("a", "c1"), derived("b", "c1")),
        List.of(stored("a", "c1"), stored("z", "c9")));
    assertThat(plan.open()).extracting(FindingDoc::contract).containsExactly("b");
    assertThat(plan.unchanged()).extracting(FindingDoc::contract).containsExactly("a");
    assertThat(plan.close()).extracting(StoredFinding::contractId).containsExactly("z");
  }

  @Test
  void sameContractDifferentChangeIsANewFinding() {
    var plan = FindingReconciler.plan(List.of(derived("a", "c2")), List.of(stored("a", "c1")));
    assertThat(plan.open()).hasSize(1);
    assertThat(plan.close()).hasSize(1);
  }
}
