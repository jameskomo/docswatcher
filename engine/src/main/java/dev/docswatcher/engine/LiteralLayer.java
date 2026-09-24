package dev.docswatcher.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regex rules over file contents. */
final class LiteralLayer {

  private LiteralLayer() {}

  static void run(List<Provider> providers, List<SourceFile> files, Accumulator acc) {
    Map<String, Pattern> compiled = new HashMap<>();
    Map<String, List<Glob>> globs = new HashMap<>();
    for (Provider p : providers) {
      for (Provider.Literal rule : p.detectors().literals()) {
        Pattern pattern = compiled.computeIfAbsent(rule.id(), x -> Pattern.compile(rule.pattern()));
        List<Glob> include = globs.computeIfAbsent(rule.id() + "#in", x -> compile(rule.files()));
        List<Glob> exclude = globs.computeIfAbsent(rule.id() + "#ex", x -> compile(rule.exclude()));
        List<String> anchors = anchors(rule.pattern());
        for (SourceFile f : files) {
          if (!Glob.anyMatch(include, f.path) || Glob.anyMatch(exclude, f.path)) continue;
          if (anchors != null && !containsAny(f.text, anchors)) continue;
          // A team's own pattern has not been reviewed the way the knowledge base's have, and on a
          // server one repository's pattern must not hold the scan worker: it gets a time budget.
          boolean own = p.id().startsWith(OwnKnowledge.PREFIX);
          Matcher m = pattern.matcher(own ? new Budgeted(f.text, rule.id(), f.path) : f.text);
          while (m.find()) {
            int at = m.groupCount() >= 1 && m.start(1) >= 0 ? m.start(1) : m.start();
            String key = expand(rule.key(), m);
            Evidence e = new Evidence(f.path, f.lineAt(at), f.columnAt(at), f.snippetAt(at), rule.id(), "literal");
            acc.add(p.id(), rule.kind(), key, e, rule.confidence());
          }
        }
      }
    }
  }

  /** How long one of a team's own patterns may run over one file. */
  static final long OWN_PATTERN_BUDGET_NANOS = 2_000_000_000L;

  /**
   * File text that stops a regex which has run past its budget. The regex engine reads its input
   * only through charAt, so a pattern that backtracks without end stops here, with its name.
   */
  static final class Budgeted implements CharSequence {
    private final String text;
    private final String rule;
    private final String path;
    private final long deadline;
    private int reads;

    Budgeted(String text, String rule, String path) {
      this(text, rule, path, System.nanoTime() + OWN_PATTERN_BUDGET_NANOS);
    }

    Budgeted(String text, String rule, String path, long deadline) {
      this.text = text;
      this.rule = rule;
      this.path = path;
      this.deadline = deadline;
    }

    @Override
    public char charAt(int index) {
      if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadline) {
        throw new IllegalStateException(rule + ": pattern ran longer than " + OWN_PATTERN_BUDGET_NANOS / 1_000_000_000L
            + " seconds on " + path + "; simplify it (docs/19-your-own-apis.md)");
      }
      return text.charAt(index);
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }

    @Override
    public String toString() {
      return text;
    }
  }

  private static List<Glob> compile(List<String> patterns) {
    List<Glob> out = new ArrayList<>();
    for (String s : patterns) out.add(Glob.of(s));
    return out;
  }

  private static boolean containsAny(String text, List<String> anchors) {
    for (String a : anchors) if (text.contains(a)) return true;
    return false;
  }

  /**
   * Strings of which every match of {@code regex} starts with one, or null when that cannot be
   * told. A file holding none of them cannot match, and String.contains finds that out far faster
   * than a pattern that opens with \b or an alternation, which the regex engine tries at every
   * offset of the file.
   *
   * <p>Understands an optional leading \b, then either top-level alternatives or one group of
   * them, each opening with plain or escaped characters. Anything else gives null, so the pattern
   * is simply run on every file as before.
   */
  static List<String> anchors(String regex) {
    if (regex.contains("\\Q")) return null;
    String rest = regex.startsWith("\\b") ? regex.substring(2) : regex;
    List<String> branches;
    if (rest.startsWith("(")) {
      int close = skip(rest, 0);
      if (close < 0) return null;
      String body = rest.substring(1, close - 1);
      if (body.startsWith("?:")) body = body.substring(2);
      else if (body.startsWith("?")) return null; // a lookaround or a named group
      String after = rest.substring(close);
      if (!after.isEmpty() && "?*+{".indexOf(after.charAt(0)) >= 0) return null;
      List<String> tail = alternatives(after);
      if (tail == null || tail.size() != 1) return null;
      branches = alternatives(body);
    } else {
      branches = alternatives(rest);
    }
    if (branches == null) return null;
    List<String> out = new ArrayList<>();
    for (String b : branches) {
      String prefix = literalPrefix(b);
      if (prefix.isEmpty()) return null;
      out.add(prefix);
    }
    return out;
  }

  /** Splits at top-level |, or null when the text is not well formed. */
  private static List<String> alternatives(String s) {
    List<String> out = new ArrayList<>();
    int start = 0;
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '|') {
        out.add(s.substring(start, i));
        start = ++i;
      } else if (c == ')') {
        return null;
      } else {
        i = skip(s, i);
        if (i < 0) return null;
      }
    }
    out.add(s.substring(start));
    return out;
  }

  /** The index after the atom at {@code i}: an escape, a character class, a group or one char; -1 if malformed. */
  private static int skip(String s, int i) {
    char c = s.charAt(i);
    if (c == '\\') return i + 2 <= s.length() ? i + 2 : -1;
    if (c == '[') {
      int j = i + 1;
      if (j < s.length() && s.charAt(j) == '^') j++;
      if (j < s.length() && s.charAt(j) == ']') return -1;
      while (j < s.length()) {
        char d = s.charAt(j);
        if (d == '\\') j += 2;
        else if (d == '[') return -1;
        else if (d == ']') return j + 1;
        else j++;
      }
      return -1;
    }
    if (c == '(') {
      int j = i + 1;
      while (j < s.length() && s.charAt(j) != ')') {
        j = skip(s, j);
        if (j < 0) return -1;
      }
      return j < s.length() ? j + 1 : -1;
    }
    return i + 1;
  }

  /** The characters a match of this alternative must open with. */
  private static String literalPrefix(String b) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (i < b.length()) {
      char c = b.charAt(i);
      char literal;
      int next;
      if (c == '\\') {
        if (i + 1 >= b.length() || Character.isLetterOrDigit(b.charAt(i + 1))) break;
        literal = b.charAt(i + 1);
        next = i + 2;
      } else if (".[]()^$|?*+{}".indexOf(c) >= 0) {
        break;
      } else {
        literal = c;
        next = i + 1;
      }
      // A character that may occur zero times is not a character every match has.
      if (next < b.length() && "?*{".indexOf(b.charAt(next)) >= 0) break;
      sb.append(literal);
      if (next < b.length() && b.charAt(next) == '+') break;
      i = next;
    }
    return sb.toString();
  }

  /** Expands $1..$9 in a key template. */
  static String expand(String template, Matcher m) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);
      if (c == '$' && i + 1 < template.length() && Character.isDigit(template.charAt(i + 1))) {
        int g = template.charAt(i + 1) - '0';
        String v = g <= m.groupCount() ? m.group(g) : null;
        sb.append(v == null ? "" : v);
        i++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
