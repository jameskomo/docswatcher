package dev.docswatcher.engine;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tree-sitter query rules, run only for providers whose SDK package appears in a manifest. */
final class CallsiteLayer {

  private final Grammars grammars = new Grammars();
  private final Map<String, TSQuery> queries = new HashMap<>();

  void run(List<Provider> providers, List<SourceFile> files, Accumulator acc) {
    // Group active rules by rule language.
    Map<String, List<Rule>> byLanguage = new HashMap<>();
    for (Provider p : providers) {
      for (Provider.Callsite c : p.detectors().callsites()) {
        if (!acc.hasManifest(p.id(), c.requires())) continue;
        byLanguage.computeIfAbsent(c.language(), x -> new ArrayList<>()).add(new Rule(p, c));
      }
    }
    if (byLanguage.isEmpty()) return;

    try (TSParser parser = new TSParser()) {
      for (SourceFile f : files) {
        String fileLanguage = Paths.language(f.path);
        if (fileLanguage == null) continue;
        List<Rule> rules = byLanguage.get(Grammars.ruleLanguage(fileLanguage));
        if (rules == null || rules.isEmpty()) continue;
        TSLanguage lang = grammars.get(fileLanguage);
        parser.setLanguage(lang);
        try (TSTree tree = parser.parseString(null, f.text)) {
          TSNode root = tree.getRootNode();
          for (Rule r : rules) {
            TSQuery query = queries.computeIfAbsent(r.rule.id() + "@" + fileLanguage, x -> new TSQuery(lang, r.rule.query()));
            try (TSQueryCursor cursor = new TSQueryCursor()) {
              // The binding evaluates #eq? and friends itself, but only when given the source text.
              cursor.exec(query, root, f.text);
              TSQueryMatch match = new TSQueryMatch();
              while (cursor.nextMatch(match)) {
                TSNode call = captureNamed(query, match, "call");
                if (call == null) continue;
                int at = f.charOffsetForByte(call.getStartByte());
                Evidence e = new Evidence(f.path, f.lineAt(at), f.columnAt(at), f.snippetAt(at), r.rule.id(), "callsite");
                acc.add(r.provider.id(), r.rule.kind(), r.rule.key(), e, r.rule.confidence());
                if (r.rule.mapsTo() != null) {
                  acc.add(r.provider.id(), r.rule.mapsTo().kind(), r.rule.mapsTo().key(), e, r.rule.confidence());
                }
              }
            }
          }
        }
      }
    }
  }

  private static TSNode captureNamed(TSQuery query, TSQueryMatch match, String name) {
    for (TSQueryCapture c : match.getCaptures()) {
      if (name.equals(query.getCaptureNameForId(c.getIndex()))) return c.getNode();
    }
    return null;
  }

  /** Compiles every query in the knowledge base; used by the validator. */
  static List<String> compileAll(List<Provider> providers) {
    List<String> errors = new ArrayList<>();
    Grammars g = new Grammars();
    for (Provider p : providers) {
      for (Provider.Callsite c : p.detectors().callsites()) {
        try {
          TSLanguage lang = g.get(c.language());
          try (TSQuery q = new TSQuery(lang, c.query())) {
            boolean hasCall = false;
            for (int i = 0; i < q.getCaptureCount(); i++) if ("call".equals(q.getCaptureNameForId(i))) hasCall = true;
            if (!hasCall) errors.add(c.id() + ": query has no @call capture");
          }
        } catch (Exception e) {
          errors.add(c.id() + ": query does not compile for " + c.language() + ": " + e.getMessage());
        }
      }
    }
    return errors;
  }

  private record Rule(Provider provider, Provider.Callsite rule) {}
}
