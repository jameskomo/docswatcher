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
        for (SourceFile f : files) {
          if (!Glob.anyMatch(include, f.path) || Glob.anyMatch(exclude, f.path)) continue;
          Matcher m = pattern.matcher(f.text);
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

  private static List<Glob> compile(List<String> patterns) {
    List<Glob> out = new ArrayList<>();
    for (String s : patterns) out.add(Glob.of(s));
    return out;
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
