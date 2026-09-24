package dev.docswatcher.app.scan;

import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.store.StoredFinding;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Pure: decides which findings open and which close, given what the matcher derived and what is stored. */
public final class FindingReconciler {

  public record Plan(List<FindingDoc> open, List<FindingDoc> unchanged, List<StoredFinding> close) {}

  private FindingReconciler() {}

  /**
   * As {@link #plan(List, List)}, for a match that only knew some providers. A stored finding of a
   * provider it did not know, such as a team's own records that were invalid this time or that a
   * rematch never reads, is neither closed nor carried: nothing was learned about it.
   */
  public static Plan plan(List<FindingDoc> derived, List<StoredFinding> stored, Predicate<String> knownProvider) {
    return plan(derived, stored.stream().filter(f -> knownProvider.test(provider(f.contractId()))).toList());
  }

  /** The provider of a contract id, provider:kind:key. */
  static String provider(String contractId) {
    int colon = contractId.indexOf(':');
    return colon < 0 ? contractId : contractId.substring(0, colon);
  }

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
