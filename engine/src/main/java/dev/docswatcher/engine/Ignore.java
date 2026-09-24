package dev.docswatcher.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

  /** One pattern line, relative to the directory of the file it came from. */
  private record Rule(Pattern regex, boolean negate, boolean dirOnly, boolean nameOnly) {}

  private static final Ignore NONE = new Ignore(List.of(), Map.of(), List.of());

  // The last matching rule decides, trying the root .gitignore first, then the .gitignore of each
  // directory above the path from the top down, then .docswatcherignore, then this run's patterns.
  // Rules are kept by the directory they are scoped to, so a path only meets the rules that can
  // apply to it: a monorepo with hundreds of .gitignore files would otherwise try every rule of
  // every one of them against every directory of every path.
  private final List<Rule> root;
  /** Directory -> the rules of the .gitignore in it, for every directory but the root. */
  private final Map<String, List<Rule>> nested;
  /** .docswatcherignore, then this run's patterns, relative to the root. */
  private final List<Rule> last;
  /** Directory -> whether it or a directory above it is excluded. Files share directories. */
  private final Map<String, Boolean> dirs = new ConcurrentHashMap<>();

  private Ignore(List<Rule> root, Map<String, List<Rule>> nested, List<Rule> last) {
    this.root = root;
    this.nested = nested;
    this.last = last;
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
    List<Rule> root = new ArrayList<>();
    Map<String, List<Rule>> nested = new HashMap<>();
    for (Map.Entry<String, String> e : ignoreFiles.entrySet()) {
      String p = e.getKey();
      if (p.equals(GITIGNORE)) {
        parse(root, e.getValue());
      } else if (p.endsWith("/" + GITIGNORE)) {
        List<Rule> rules = new ArrayList<>();
        parse(rules, e.getValue());
        if (!rules.isEmpty()) nested.put(dirOf(p), List.copyOf(rules));
      }
    }
    List<Rule> last = new ArrayList<>();
    if (ignoreFiles.containsKey(DOCSWATCHERIGNORE)) parse(last, ignoreFiles.get(DOCSWATCHERIGNORE));
    if (exclude != null) parse(last, String.join("\n", exclude));
    if (root.isEmpty() && nested.isEmpty() && last.isEmpty()) return NONE;
    return new Ignore(List.copyOf(root), Map.copyOf(nested), List.copyOf(last));
  }

  /** Whether a file is excluded. As in git, nothing inside an excluded directory comes back. */
  public boolean ignored(String path) {
    if (this == NONE) return false;
    int slash = path.lastIndexOf('/');
    if (slash >= 0 && dirExcluded(path.substring(0, slash))) return true;
    return decide(path, false);
  }

  /** Whether a directory, or any directory above it, is excluded. */
  private boolean dirExcluded(String dir) {
    Boolean known = dirs.get(dir);
    if (known != null) return known;
    int slash = dir.lastIndexOf('/');
    boolean excluded = (slash >= 0 && dirExcluded(dir.substring(0, slash))) || decide(dir, true);
    dirs.put(dir, excluded);
    return excluded;
  }

  private boolean decide(String target, boolean isDir) {
    boolean excluded = apply(root, target, isDir, false);
    if (!nested.isEmpty()) {
      int slash = target.indexOf('/');
      while (slash >= 0) {
        List<Rule> rules = nested.get(target.substring(0, slash));
        if (rules != null) excluded = apply(rules, target.substring(slash + 1), isDir, excluded);
        slash = target.indexOf('/', slash + 1);
      }
    }
    return apply(last, target, isDir, excluded);
  }

  /** Tries rules from one ignore file against {@code rel}, the target relative to that file. */
  private static boolean apply(List<Rule> rules, String rel, boolean isDir, boolean excluded) {
    if (rules.isEmpty()) return excluded;
    String name = rel.substring(rel.lastIndexOf('/') + 1);
    for (Rule r : rules) {
      if (r.dirOnly && !isDir) continue;
      if (r.regex.matcher(r.nameOnly ? name : rel).matches()) excluded = !r.negate;
    }
    return excluded;
  }

  private static void parse(List<Rule> out, String text) {
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
      out.add(new Rule(Pattern.compile(toRegex(line)), negate, dirOnly, !anchored));
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
