package dev.docswatcher.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Path exclusion in .gitignore syntax: every .gitignore in the tree, the root .docswatcherignore,
 * then patterns given for one run. See docs/18-excluding-paths.md.
 *
 * <p>Mirrored rule for rule by web/engine/ignore.ts. Both are checked against the same cases in
 * engine/src/test/resources/ignore-cases.json, which were themselves checked against git.
 */
public final class Ignore {

  public static final String GITIGNORE = ".gitignore";
  public static final String DOCSWATCHERIGNORE = ".docswatcherignore";

  /** One pattern line, scoped to the directory of the file it came from ("" for the root). */
  private record Rule(String base, Pattern regex, boolean negate, boolean dirOnly, boolean nameOnly) {}

  private static final Ignore NONE = new Ignore(List.of());

  private final List<Rule> rules;

  private Ignore(List<Rule> rules) {
    this.rules = rules;
  }

  public static Ignore none() {
    return NONE;
  }

  /** A file whose contents configure exclusion: any .gitignore, or .docswatcherignore at the root. */
  public static boolean isIgnoreFile(String path) {
    return path.equals(GITIGNORE) || path.endsWith("/" + GITIGNORE) || path.equals(DOCSWATCHERIGNORE);
  }

  /**
   * @param ignoreFiles path to text of the ignore files in the tree; others are disregarded
   * @param exclude patterns for this run, applied last and relative to the root
   */
  public static Ignore of(Map<String, String> ignoreFiles, List<String> exclude) {
    List<Rule> rules = new ArrayList<>();
    // Shallower .gitignore files first, so a deeper one can override; then .docswatcherignore;
    // then this run's patterns. The last matching rule decides.
    List<String> gitignores = ignoreFiles.keySet().stream()
        .filter(p -> p.equals(GITIGNORE) || p.endsWith("/" + GITIGNORE))
        .sorted(Comparator.comparingInt((String p) -> p.split("/").length).thenComparing(p -> p))
        .toList();
    for (String p : gitignores) parse(rules, dirOf(p), ignoreFiles.get(p));
    if (ignoreFiles.containsKey(DOCSWATCHERIGNORE)) parse(rules, "", ignoreFiles.get(DOCSWATCHERIGNORE));
    if (exclude != null) parse(rules, "", String.join("\n", exclude));
    return rules.isEmpty() ? NONE : new Ignore(List.copyOf(rules));
  }

  /** Whether a file is excluded. As in git, nothing inside an excluded directory comes back. */
  public boolean ignored(String path) {
    if (rules.isEmpty()) return false;
    int slash = path.indexOf('/');
    while (slash >= 0) {
      if (decide(path.substring(0, slash), true)) return true;
      slash = path.indexOf('/', slash + 1);
    }
    return decide(path, false);
  }

  private boolean decide(String target, boolean isDir) {
    boolean excluded = false;
    for (Rule r : rules) {
      if (r.dirOnly && !isDir) continue;
      String rel;
      if (r.base.isEmpty()) {
        rel = target;
      } else if (target.startsWith(r.base + "/")) {
        rel = target.substring(r.base.length() + 1);
      } else {
        continue;
      }
      String subject = r.nameOnly ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
      if (r.regex.matcher(subject).matches()) excluded = !r.negate;
    }
    return excluded;
  }

  private static void parse(List<Rule> out, String base, String text) {
    if (text == null) return;
    for (String raw : text.split("\r?\n")) {
      String line = raw.stripTrailing();
      if (line.isEmpty() || line.startsWith("#")) continue;
      boolean negate = false;
      if (line.startsWith("\\#") || line.startsWith("\\!")) {
        line = line.substring(1);
      } else if (line.startsWith("!")) {
        negate = true;
        line = line.substring(1);
      }
      boolean dirOnly = line.endsWith("/");
      while (line.endsWith("/")) line = line.substring(0, line.length() - 1);
      if (line.isEmpty()) continue;
      // A slash anywhere but the end anchors the pattern to its file's directory.
      boolean anchored = line.contains("/");
      if (line.startsWith("/")) line = line.substring(1);
      out.add(new Rule(base, Pattern.compile(toRegex(line)), negate, dirOnly, !anchored));
    }
  }

  /** Glob to a full-match regex: * and ? stay in one segment, ** crosses them. */
  static String toRegex(String glob) {
    StringBuilder sb = new StringBuilder();
    int n = glob.length();
    int i = 0;
    while (i < n) {
      char c = glob.charAt(i);
      if (i == 0 && glob.startsWith("**/")) {
        sb.append("(?:.*/)?");
        i += 3;
      } else if (c == '/' && glob.startsWith("/**/", i)) {
        sb.append("/(?:.*/)?");
        i += 4;
      } else if (c == '/' && glob.startsWith("/**", i) && i + 3 == n) {
        sb.append("/.*");
        i += 3;
      } else if (c == '*' && i + 1 < n && glob.charAt(i + 1) == '*') {
        sb.append(".*");
        i += 2;
      } else if (c == '*') {
        sb.append("[^/]*");
        i++;
      } else if (c == '?') {
        sb.append("[^/]");
        i++;
      } else if (c == '[' && glob.indexOf(']', i + 1) > i + 1) {
        int close = glob.indexOf(']', i + 1);
        String body = glob.substring(i + 1, close);
        if (body.startsWith("!")) body = "^" + body.substring(1);
        sb.append('[').append(body.replace("\\", "\\\\")).append(']');
        i = close + 1;
      } else {
        if ("\\.^$|+(){}[]".indexOf(c) >= 0) sb.append('\\');
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  private static String dirOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }
}
