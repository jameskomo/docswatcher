package dev.docswatcher.app.alerts;

import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * When a warning is due. The one rule both kinds of alert share.
 *
 * <p>A date is warned about at a threshold once it is that many days away or fewer, and not yet
 * past. Only the nearest threshold it has crossed matters: a finding first seen five days out gets
 * one warning, not a 30-day one and a 7-day one together, and one that crossed 30 days on a day
 * the job did not run is warned about the next day instead. Sending records every threshold
 * crossed, so a threshold passed over is never sent late.
 */
public final class Thresholds {

  /** The Teams page's promise: 30 and 7 days ahead. */
  public static final List<Integer> DEFAULT = List.of(30, 7);
  static final int MAX_DAYS = 365;
  static final int MAX_COUNT = 4;

  private Thresholds() {}

  /** Every threshold the date has crossed: those at least {@code days} away. None once the date has passed. */
  public static List<Integer> crossed(long days, Collection<Integer> thresholds) {
    if (days < 0) {
      return List.of();
    }
    return thresholds.stream().filter(t -> days <= t).sorted().toList();
  }

  /** The threshold to warn at now, if any: the nearest one crossed, unless it was already sent. */
  public static OptionalInt due(long days, Collection<Integer> thresholds, Collection<Integer> sent) {
    List<Integer> crossed = crossed(days, thresholds);
    if (crossed.isEmpty() || sent.contains(crossed.get(0))) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(crossed.get(0));
  }

  /** A team's choice, checked: one to four whole days between 1 and 365, largest first. */
  public static List<Integer> normalise(Collection<Integer> requested) {
    if (requested == null || requested.isEmpty()) {
      throw new IllegalArgumentException("Choose at least one number of days.");
    }
    TreeSet<Integer> set = new TreeSet<>();
    for (Integer t : requested) {
      if (t == null || t < 1 || t > MAX_DAYS) {
        throw new IllegalArgumentException("Days before the date must be between 1 and " + MAX_DAYS + ".");
      }
      set.add(t);
    }
    if (set.size() > MAX_COUNT) {
      throw new IllegalArgumentException("Choose at most " + MAX_COUNT + " warnings.");
    }
    return List.copyOf(set.descendingSet());
  }
}
