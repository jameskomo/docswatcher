package dev.docswatcher.engine;

import org.treesitter.TSInputEncoding;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParseState;
import org.treesitter.TSParser;
import org.treesitter.TSParserProgress;
import org.treesitter.TSPoint;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSQueryPredicateStep;
import org.treesitter.TSQueryPredicateStepType;
import org.treesitter.TSReader;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Tree-sitter query rules, run only for providers whose SDK package appears in a manifest. */
final class CallsiteLayer {

  /**
   * Time allowed for parsing and querying one file. A megabyte of ordinary source parses in a
   * few seconds at most; a file that takes longer is left out of the callsite layer rather than
   * holding up the scan.
   */
  static final Duration FILE_BUDGET = Duration.ofSeconds(10);

  /**
   * The longest run of anonymous sibling nodes (punctuation, keywords) a tree may contain and
   * still be queried. The query cursor's work grows with the square of such a run, and real code
   * has runs of a handful: ten thousand consecutive open brackets is not source.
   */
  static final int MAX_ANONYMOUS_RUN = 256;

  /** The deepest tree that is queried. Query time climbs steeply past a few ten thousand levels. */
  static final int MAX_DEPTH = 10_000;

  private static final int READ_CHUNK = 64 * 1024;

  private final Grammars grammars = new Grammars();
  private final Map<String, Compiled> queries = new HashMap<>();
  private final Duration budget;

  CallsiteLayer() {
    this(FILE_BUDGET);
  }

  CallsiteLayer(Duration budget) {
    this.budget = budget;
  }

  void run(List<Provider> providers, List<SourceFile> files, Accumulator acc) {
    run(providers, files, acc, () -> false);
  }

  /**
   * As above, stopping before the next file once {@code expired} says the scan is out of time.
   * Returns how many files were left unreached then, and 0 when every file was considered.
   */
  int run(List<Provider> providers, List<SourceFile> files, Accumulator acc, BooleanSupplier expired) {
    // Group active rules by rule language.
    Map<String, List<Rule>> byLanguage = new HashMap<>();
    for (Provider p : providers) {
      for (Provider.Callsite c : p.detectors().callsites()) {
        if (!acc.hasManifest(p.id(), c.requires())) continue;
        byLanguage.computeIfAbsent(c.language(), x -> new ArrayList<>()).add(new Rule(p, c));
      }
    }
    if (byLanguage.isEmpty()) return 0;

    byte[] chunk = new byte[READ_CHUNK];
    try (TSParser parser = new TSParser()) {
      for (int i = 0; i < files.size(); i++) {
        if (expired.getAsBoolean()) return files.size() - i;
        SourceFile f = files.get(i);
        String fileLanguage = Paths.language(f.path);
        if (fileLanguage == null) continue;
        List<Rule> rules = byLanguage.get(Grammars.ruleLanguage(fileLanguage));
        if (rules == null || rules.isEmpty()) continue;
        TSLanguage lang = grammars.get(fileLanguage);
        // Parsing and querying are most of the cost of a scan, yet few files can match: every #eq?
        // string of a query pattern must appear in a file for it to match there. See Compiled.
        List<Compiled> candidates = new ArrayList<>();
        for (Rule r : rules) {
          Compiled c = queries.computeIfAbsent(r.rule.id() + "@" + fileLanguage, x -> Compiled.of(r, lang));
          if (c.mayMatch(f.text)) candidates.add(c);
        }
        if (candidates.isEmpty()) continue;
        parser.setLanguage(lang);
        BoundedInput input = new BoundedInput(f.text.getBytes(StandardCharsets.UTF_8), System.nanoTime() + budget.toNanos());
        TSTree parsed = parser.parseWithOptions(chunk, null, input, TSInputEncoding.TSInputEncodingUTF8, input);
        if (parsed == null) {
          // Out of time. A cancelled parse resumes on the next call unless the parser is reset.
          parser.reset();
          continue;
        }
        try (TSTree tree = parsed) {
          TSNode root = tree.getRootNode();
          if (!queryable(root, input)) continue;
          for (Compiled c : candidates) {
            if (input.expired()) break;
            Rule r = c.rule;
            TSQuery query = c.query;
            try (TSQueryCursor cursor = new TSQueryCursor()) {
              // The binding evaluates #eq? and friends itself, but only when given the source text.
              cursor.exec(query, root, f.text);
              TSQueryMatch match = new TSQueryMatch();
              while (!input.expired() && cursor.nextMatch(match)) {
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
    return 0;
  }

  /**
   * False when the tree is deeper than {@link #MAX_DEPTH}, has a run of anonymous siblings longer
   * than {@link #MAX_ANONYMOUS_RUN}, or time ran out. Whether a node is named costs a native round
   * trip, so it is only asked in sibling lists already longer than the run limit; a run that
   * straddles that point is caught once it reaches twice the limit.
   */
  static boolean queryable(TSNode root, BoundedInput input) {
    int[] siblings = new int[64];
    int[] runs = new int[64];
    int depth = 0;
    long visited = 0;
    try (TSTreeCursor cursor = new TSTreeCursor(root)) {
      boolean down = true;
      while (true) {
        if (down) {
          if ((++visited & 1023) == 0 && input.expired()) return false;
          if (++siblings[depth] > MAX_ANONYMOUS_RUN) {
            runs[depth] = cursor.currentNode().isNamed() ? 0 : runs[depth] + 1;
            if (runs[depth] > MAX_ANONYMOUS_RUN) return false;
          }
          if (cursor.gotoFirstChild()) {
            if (++depth > MAX_DEPTH) return false;
            if (depth == runs.length) {
              runs = Arrays.copyOf(runs, depth * 2);
              siblings = Arrays.copyOf(siblings, depth * 2);
            }
            siblings[depth] = 0;
            runs[depth] = 0;
            continue;
          }
        }
        if (cursor.gotoNextSibling()) {
          down = true;
          continue;
        }
        if (!cursor.gotoParent()) return true;
        depth--;
        down = false;
      }
    }
  }

  /**
   * One file's UTF-8, handed to the parser in chunks, with a deadline the parser checks as it
   * goes. Both methods are called from native code, so neither may throw.
   */
  static final class BoundedInput implements TSReader, TSParserProgress {

    private final byte[] bytes;
    private final long deadline;

    BoundedInput(byte[] bytes, long deadline) {
      this.bytes = bytes;
      this.deadline = deadline;
    }

    @Override
    public int read(byte[] buf, int offset, TSPoint position) {
      if (offset < 0 || offset >= bytes.length) return 0;
      int n = Math.min(buf.length, bytes.length - offset);
      System.arraycopy(bytes, offset, buf, 0, n);
      return n;
    }

    /** Returning true cancels the parse. */
    @Override
    public boolean progress(TSParseState state) {
      return expired();
    }

    boolean expired() {
      return System.nanoTime() - deadline > 0;
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

  record Rule(Provider provider, Provider.Callsite rule) {}

  /**
   * A rule's query compiled for one grammar, with the text each of its patterns needs.
   *
   * <p>{@code (#eq? @capture "text")} holds only when a captured node's text is exactly "text"
   * (the binding rejects a match that captured no node), and a node's text is a slice of the
   * file. So a pattern cannot match a file that lacks any of its #eq? strings, and a query whose
   * every pattern is ruled out that way matches nothing: skipping it changes no result.
   */
  record Compiled(Rule rule, TSQuery query, List<List<String>> needs) {

    static Compiled of(Rule rule, TSLanguage lang) {
      TSQuery query = new TSQuery(lang, rule.rule().query());
      return new Compiled(rule, query, needs(query));
    }

    boolean mayMatch(String text) {
      for (List<String> pattern : needs) {
        boolean all = true;
        for (String s : pattern) {
          if (!text.contains(s)) {
            all = false;
            break;
          }
        }
        if (all) return true;
      }
      return false;
    }

    /** Per pattern, the strings named by its {@code (#eq? @capture "string")} predicates. */
    static List<List<String>> needs(TSQuery query) {
      List<List<String>> out = new ArrayList<>();
      for (int p = 0; p < query.getPatternCount(); p++) {
        List<String> strings = new ArrayList<>();
        TSQueryPredicateStep[] steps = query.getPredicateForPattern(p);
        int start = 0;
        for (int i = 0; steps != null && i < steps.length; i++) {
          if (steps[i].getType() != TSQueryPredicateStepType.TSQueryPredicateStepTypeDone) continue;
          if (i - start == 3
              && steps[start].getType() == TSQueryPredicateStepType.TSQueryPredicateStepTypeString
              && "eq?".equals(query.getStringValueForId(steps[start].getValueId()))
              && steps[start + 1].getType() == TSQueryPredicateStepType.TSQueryPredicateStepTypeCapture
              && steps[start + 2].getType() == TSQueryPredicateStepType.TSQueryPredicateStepTypeString) {
            strings.add(query.getStringValueForId(steps[start + 2].getValueId()));
          }
          start = i + 1;
        }
        out.add(List.copyOf(strings));
      }
      return out;
    }
  }
}
