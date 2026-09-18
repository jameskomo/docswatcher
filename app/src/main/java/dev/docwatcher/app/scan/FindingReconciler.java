package dev.docwatcher.app.scan;

import dev.docwatcher.app.model.FindingDoc;
import dev.docwatcher.app.store.StoredFinding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure: decides which findings open and which close, given what the matcher derived and what is stored. */
public final class FindingReconciler {

  public record Plan(List<FindingDoc> open, List<FindingDoc> unchanged, List<StoredFinding> close) {}

  private FindingReconciler() {}

  public static Plan plan(List<FindingDoc> derived, List<StoredFinding> stored) {
    Set<String> storedKeys = new HashSet<>();
    for (StoredFinding f : stored) {
      storedKeys.add(key(f.contractId(), f.changeId()));
    }
    Set<String> derivedKeys = new HashSet<>();
    List<FindingDoc> open = new ArrayList<>();
    List<FindingDoc> unchanged = new ArrayList<>();
    for (FindingDoc f : derived) {
      derivedKeys.add(key(f.contract(), f.change()));
      if (storedKeys.contains(key(f.contract(), f.change()))) {
        unchanged.add(f);
      } else {
        open.add(f);
      }
    }
    List<StoredFinding> close = new ArrayList<>();
    for (StoredFinding f : stored) {
      if (!derivedKeys.contains(key(f.contractId(), f.changeId()))) {
        close.add(f);
      }
    }
    return new Plan(open, unchanged, close);
  }

  private static String key(String contract, String change) {
    return contract + "|" + change;
  }
}
