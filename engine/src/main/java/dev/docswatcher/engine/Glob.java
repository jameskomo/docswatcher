package dev.docswatcher.engine;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Glob matching over slash-separated relative paths, with the same semantics in both engines:
 * "**" crosses directories, "**\/" also matches nothing so "**\/*.java" matches a top-level file,
 * "*" and "?" stay inside one segment, and "{a,b}" alternates.
 */
final class Glob {

  private final Pattern pattern;

  private Glob(Pattern p) {
    this.pattern = p;
  }

  static Glob of(String glob) {
    StringBuilder re = new StringBuilder("^");
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
            re.append("(?:.*/)?");
            i += 3;
          } else {
            re.append(".*");
            i += 2;
          }
        } else {
          re.append("[^/]*");
          i++;
        }
      } else if (c == '?') {
        re.append("[^/]");
        i++;
      } else if (c == '{') {
        int close = glob.indexOf('}', i);
        if (close < 0) throw new IllegalArgumentException("Unclosed { in glob " + glob);
        String[] alts = glob.substring(i + 1, close).split(",");
        re.append("(?:");
        for (int k = 0; k < alts.length; k++) {
          if (k > 0) re.append('|');
          re.append(Pattern.quote(alts[k]));
        }
        re.append(')');
        i = close + 1;
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
        i++;
      }
    }
    re.append('$');
    return new Glob(Pattern.compile(re.toString()));
  }

  boolean matches(String relativePath) {
    return pattern.matcher(relativePath).matches();
  }

  static boolean anyMatch(List<Glob> globs, String path) {
    for (Glob g : globs) if (g.matches(path)) return true;
    return false;
  }
}
