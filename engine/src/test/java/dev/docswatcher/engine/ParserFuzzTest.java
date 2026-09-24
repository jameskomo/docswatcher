package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.treesitter.TSInputEncoding;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TSTreeCursor;

/**
 * Feeds every grammar random, truncated, deeply nested and oversized input, through the parser
 * directly and through the callsite layer with every rule switched on. The assertion that
 * matters is that the test JVM is still alive at the end; surefire reports a fork that died.
 *
 * <p>Not part of the default build. Run with
 * {@code ./mvnw -pl engine -am test -Dtest=ParserFuzzTest -DexcludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
 * -Dfuzz.seconds=300 -Dfuzz.seed=42}.
 */
@Tag("fuzz")
class ParserFuzzTest {

  private static final int MAX = (int) FileTree.MAX_BYTES;

  private static final Map<String, String> SEEDS = new LinkedHashMap<>();

  static {
    SEEDS.put("java", "package a;\nimport com.stripe.model.Source;\nclass A<T extends B> {\n  @Override Object m(int[] x) throws E {\n    var r = Source.create(Map.of(\"a\", 1));\n    return switch (x[0]) { case 1 -> (a) -> a + 1; default -> \"\"\"\n text\n \"\"\"; };\n  }\n}\n");
    SEEDS.put("python", "from openai import OpenAI\nclient = OpenAI()\n@dec\nasync def f(x: int = 1, *a, **k) -> None:\n    r = await client.chat.completions.create(model=f\"{x!r:>{w}}\")\n    return [y for y in x if y] or {k: v for k, v in a}\n");
    SEEDS.put("typescript", "import Anthropic from '@anthropic-ai/sdk';\nconst c = new Anthropic();\nexport async function f<T extends keyof U>(a: T): Promise<void> {\n  const r = await c.messages.create({ model: `m-${a}` } as const satisfies X);\n  type Q = T extends [infer H, ...infer R] ? H : never;\n}\n");
    SEEDS.put("tsx", "import OpenAI from 'openai';\nexport const C = <T,>(p: P<T>) => <div a={p.x} {...p}>{p.items.map((i) => <b key={i}>{`${i}`}</b>)}</div>;\nconst r = await client.chat.completions.create({});\n");
    SEEDS.put("javascript", "const OpenAI = require('openai');\nconst client = new OpenAI();\nclass A { #p = 1; static async *g() { yield* x; } }\nconst r = await client.chat.completions.create({ m: /re[g]+x/gi, t: `a${b`c${d}`}` });\n");
    SEEDS.put("go", "package main\nimport \"github.com/sashabaranov/go-openai\"\nfunc f[T any](x T) (int, error) {\n  r, err := c.CreateChatCompletion(ctx, openai.ChatCompletionRequest{Model: `m`})\n  go func() { select { case <-ch: } }()\n  return 0, err\n}\n");
  }

  private static final Map<String, String> EXTENSION = Map.of(
      "java", "java", "python", "py", "typescript", "ts", "tsx", "tsx", "javascript", "js", "go", "go");

  private final Knowledge knowledge = TestSupport.knowledge();

  @Test
  void theParserSurvivesHostileInput() {
    long seconds = Long.getLong("fuzz.seconds", 20);
    long seed = Long.getLong("fuzz.seed", System.nanoTime());
    Random random = new Random(seed);
    System.out.println("parser fuzz: seed " + seed + ", " + seconds + "s");

    List<Map.Entry<String, Function<Random, byte[]>>> generators = generators();
    Grammars grammars = new Grammars();
    Map<String, Stats> stats = new TreeMap<>();
    long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    byte[] chunk = new byte[64 * 1024];
    int cases = 0;

    try (TSParser parser = new TSParser()) {
      while (System.nanoTime() < end) {
        var generator = generators.get(random.nextInt(generators.size()));
        for (String language : SEEDS.keySet()) {
          byte[] raw = generator.getValue().apply(random);
          // Exactly what FileTree hands the engine: bytes decoded as UTF-8 with replacement.
          String text = new String(raw, StandardCharsets.UTF_8);
          byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
          Stats s = stats.computeIfAbsent(language + " " + generator.getKey(), x -> new Stats());

          parser.setLanguage(grammars.get(language));
          long start = System.nanoTime();
          CallsiteLayer.BoundedInput input = new CallsiteLayer.BoundedInput(utf8, start + CallsiteLayer.FILE_BUDGET.toNanos());
          TSTree tree = parser.parseWithOptions(chunk, null, input, TSInputEncoding.TSInputEncodingUTF8, input);
          long ms = (System.nanoTime() - start) / 1_000_000;
          if (tree == null) {
            parser.reset();
            s.cancelled++;
          } else {
            try (TSTree t = tree) {
              TSNode root = t.getRootNode();
              assertThat(root.getEndByte()).isLessThanOrEqualTo(utf8.length);
              s.nodes = Math.max(s.nodes, walk(root));
            }
          }
          s.record(ms, utf8.length);

          if (!"go".equals(language)) {
            long layerStart = System.nanoTime();
            scanWithEveryRule(new SourceFile("f." + EXTENSION.get(language), text));
            s.layerMaxMs = Math.max(s.layerMaxMs, (System.nanoTime() - layerStart) / 1_000_000);
          }
          cases++;
        }
      }
    }

    System.out.println("parser fuzz: " + cases + " cases, JVM alive");
    System.out.printf("%-34s %6s %8s %9s %9s %10s %10s%n", "grammar / generator", "runs", "cancel", "maxMs", "layerMs", "maxBytes", "maxNodes");
    stats.forEach((k, s) -> System.out.printf("%-34s %6d %8d %9d %9d %10d %10d%n", k, s.runs, s.cancelled, s.maxMs, s.layerMaxMs, s.maxBytes, s.nodes));
    assertThat(cases).isPositive();
  }

  /** Visits every node, so the tree is read end to end as the query cursor would. */
  private static long walk(TSNode root) {
    long n = 0;
    try (TSTreeCursor cursor = new TSTreeCursor(root)) {
      boolean down = true;
      while (true) {
        if (down) n++;
        if (down && cursor.gotoFirstChild()) continue;
        if (cursor.gotoNextSibling()) {
          down = true;
          continue;
        }
        if (!cursor.gotoParent()) return n;
        down = false;
      }
    }
  }

  private void scanWithEveryRule(SourceFile file) {
    Accumulator acc = new Accumulator();
    for (Provider p : knowledge.providers()) {
      for (Provider.Callsite c : p.detectors().callsites()) {
        acc.manifestHit(p.id(), new Context.Sdk("fuzz", c.requires(), null));
      }
    }
    new CallsiteLayer().run(knowledge.providers(), List.of(file), acc);
    acc.finish();
  }

  private static List<Map.Entry<String, Function<Random, byte[]>>> generators() {
    List<Map.Entry<String, Function<Random, byte[]>>> g = new ArrayList<>();
    g.add(Map.entry("random-bytes", r -> randomBytes(r, size(r))));
    g.add(Map.entry("random-ascii", r -> randomAscii(r, size(r))));
    g.add(Map.entry("mutated-seed", ParserFuzzTest::mutatedSeed));
    g.add(Map.entry("truncated-seed", r -> {
      byte[] b = repeatTo(allSeeds(), size(r));
      return java.util.Arrays.copyOf(b, r.nextInt(b.length + 1));
    }));
    g.add(Map.entry("deep-nesting", ParserFuzzTest::deepNesting));
    g.add(Map.entry("huge-token", ParserFuzzTest::hugeToken));
    g.add(Map.entry("long-lines", r -> repeatTo(("x" + ".y(".repeat(r.nextInt(1, 2000)) + "\n").getBytes(StandardCharsets.UTF_8), size(r))));
    return g;
  }

  /** Mostly small, sometimes the full size a file may be. */
  private static int size(Random r) {
    return switch (r.nextInt(4)) {
      case 0 -> r.nextInt(64);
      case 1 -> r.nextInt(4096);
      case 2 -> r.nextInt(128 * 1024);
      default -> MAX - r.nextInt(1024);
    };
  }

  private static byte[] randomBytes(Random r, int n) {
    byte[] b = new byte[n];
    r.nextBytes(b);
    for (int i = 0; i < b.length; i++) if (b[i] == 0) b[i] = 1; // NUL marks binary; FileTree skips those.
    return b;
  }

  private static byte[] randomAscii(Random r, int n) {
    String alphabet = "(){}[]<>`'\"\\/*#$@!?:;,.=+-_|&^%~ \t\n\rabcxyz019\u00e9";
    StringBuilder sb = new StringBuilder(n);
    while (sb.length() < n) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] allSeeds() {
    return String.join("\n", SEEDS.values()).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] mutatedSeed(Random r) {
    byte[] base = repeatTo(allSeeds(), r.nextBoolean() ? 4096 : size(r));
    StringBuilder sb = new StringBuilder(new String(base, StandardCharsets.UTF_8));
    String tokens = "(){}[]<>`'\"\\/*#$@:;,.= \n\t";
    int mutations = 1 + r.nextInt(Math.max(1, sb.length() / 50 + 1));
    for (int i = 0; i < mutations && sb.length() > 0; i++) {
      int at = r.nextInt(sb.length());
      switch (r.nextInt(4)) {
        case 0 -> sb.deleteCharAt(at);
        case 1 -> sb.insert(at, tokens.charAt(r.nextInt(tokens.length())));
        case 2 -> {
          int to = Math.min(sb.length(), at + r.nextInt(64));
          if (sb.length() + (to - at) <= MAX) sb.insert(r.nextInt(sb.length()), sb.substring(at, to));
        }
        default -> sb.setCharAt(at, tokens.charAt(r.nextInt(tokens.length())));
      }
    }
    byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
    return out.length > MAX ? java.util.Arrays.copyOf(out, MAX) : out;
  }

  private static byte[] deepNesting(Random r) {
    String[][] shapes = {
      {"(", ")"}, {"[", "]"}, {"{", "}"}, {"f(", ")"}, {"a.b(", ")"}, {"<a>", "</a>"}, {"`${", "}`"},
      {"if x:\n" , ""}, {"def f():\n", ""}, {"{{", "}}"}, {"new A(", ")"}, {"x = [", "]"}, {"(a) => ", ""},
      {"/*", ""}, {"\"", ""}, {"<", ">"}, {"func() {", "}"}, {"@", ""}, {"-", ""}, {"!", ""},
    };
    String[] shape = shapes[r.nextInt(shapes.length)];
    int depth = r.nextInt(1, MAX / Math.max(1, shape[0].length() + shape[1].length() + 1));
    boolean close = r.nextBoolean();
    boolean indent = shape[0].endsWith(":\n");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < depth && sb.length() < MAX; i++) {
      if (indent) sb.append(" ".repeat(Math.min(i, 4000)));
      sb.append(shape[0]);
    }
    if (indent) sb.append(" ".repeat(Math.min(depth, 4000))).append("pass\n");
    else sb.append("x");
    if (close) for (int i = 0; i < depth && sb.length() < MAX; i++) sb.append(shape[1]);
    byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
    return out.length > MAX ? java.util.Arrays.copyOf(out, MAX) : out;
  }

  private static byte[] hugeToken(Random r) {
    int n = size(r);
    String[][] shapes = {
      {"x = ", "a"}, {"x = \"", "a"}, {"x = '", "b"}, {"x = `", "c"}, {"// ", "d"}, {"/* ", "e"}, {"# ", "f"},
      {"x = 1", "0"}, {"x = /", "g"}, {"x = 0x", "f"}, {"\"\"\"", "h"}, {"r\"", "i"}, {"x = \"", "\\u00"},
      {"s = \"", "\\"}, {"<div a=\"", "j"}, {"x = 1e", "9"}, {"`", "${a}"}, {"\u00e9", "\u00e9"}, {"x = \"", "\uD83D\uDE00"},
    };
    String[] shape = shapes[r.nextInt(shapes.length)];
    StringBuilder sb = new StringBuilder(n + 16).append(shape[0]);
    while (sb.length() < n) sb.append(shape[1]);
    if (r.nextBoolean()) sb.append(shape[0].strip());
    byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
    return out.length > MAX ? java.util.Arrays.copyOf(out, MAX) : out;
  }

  private static byte[] repeatTo(byte[] unit, int n) {
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++) out[i] = unit[i % unit.length];
    return out;
  }

  private static final class Stats {
    int runs;
    int cancelled;
    long maxMs;
    long layerMaxMs;
    int maxBytes;
    long nodes;

    void record(long ms, int bytes) {
      runs++;
      maxMs = Math.max(maxMs, ms);
      maxBytes = Math.max(maxBytes, bytes);
    }
  }
}
