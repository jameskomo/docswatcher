package dev.docwatcher.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Pure function from an inventory plus change records to findings. */
public final class Matcher {

  private static final List<String> SEVERITY_ORDER = List.of("breaking", "warning", "info");

  private Matcher() {}

  public static List<Finding> match(Inventory inv, Knowledge k, LocalDate today, boolean includeLow) {
    List<Finding> out = new ArrayList<>();
    String repo = inv.repo() == null ? "local" : inv.repo().fullName();
    for (Contract c : inv.contracts()) {
      if (!includeLow && "low".equals(c.confidence())) continue;
      for (Change ch : k.changes()) {
        if (!ch.producesFindings() || !ch.provider().equals(c.provider())) continue;
        if (!affects(ch, c)) continue;
        Integer days = ch.effective() == null
            ? null
            : (int) ChronoUnit.DAYS.between(today, LocalDate.parse(ch.effective()));
        out.add(new Finding(
            repo + ":" + ch.id() + ":" + c.id(),
            c.id(), ch.id(), ch.severity(), ch.effective(), days, c.evidence(), "open", null, null));
      }
    }
    out.sort(Comparator
        .comparing((Finding f) -> f.effective() == null ? "9999-99-99" : f.effective())
        .thenComparingInt(f -> SEVERITY_ORDER.indexOf(f.severity()))
        .thenComparing(Finding::contract)
        .thenComparing(Finding::change));
    return out;
  }

  static boolean affects(Change ch, Contract c) {
    for (Change.Affect a : ch.affects()) {
      if (a.kind().equals(c.kind()) && matches(a.kind(), a.match(), c.key())) return true;
    }
    return false;
  }

  /** Match semantics per kind. See docs/02-schemas.md, matcher semantics. */
  static boolean matches(String kind, String pattern, String key) {
    if (pattern == null) return false;
    return switch (kind) {
      case "endpoint" -> endpoint(pattern, key);
      case "api_version" -> apiVersion(pattern, key);
      default -> pattern.equals(key);
    };
  }

  private static boolean endpoint(String pattern, String key) {
    int ps = pattern.indexOf(' ');
    int ks = key.indexOf(' ');
    if (ps < 0 || ks < 0) return pattern.equals(key);
    String pm = pattern.substring(0, ps);
    String pp = pattern.substring(ps + 1);
    String km = key.substring(0, ks);
    String kp = key.substring(ks + 1);
    boolean methodOk = pm.equals("ANY") || km.equals("ANY") || pm.equals(km);
    if (!methodOk) return false;
    if (pp.indexOf('*') < 0) return pp.equals(kp);
    return Pattern.compile("^" + Pattern.quote(pp).replace("*", "\\E[^/]*\\Q") + "$").matcher(kp).matches();
  }

  private static boolean apiVersion(String pattern, String key) {
    String p = pattern.trim();
    for (String op : List.of("<=", ">=", "<", ">")) {
      if (p.startsWith(op)) {
        String v = p.substring(op.length()).trim();
        int cmp = key.compareTo(v);
        return switch (op) {
          case "<" -> cmp < 0;
          case "<=" -> cmp <= 0;
          case ">" -> cmp > 0;
          default -> cmp >= 0;
        };
      }
    }
    return p.equals(key);
  }
}
