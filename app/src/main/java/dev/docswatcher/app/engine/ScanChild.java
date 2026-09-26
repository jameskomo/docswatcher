package dev.docswatcher.app.engine;

import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.OwnKnowledge;
import dev.docswatcher.engine.RepoRef;
import dev.docswatcher.engine.ScanLimits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The entry point of a scan process: runs one {@link ScanJob} and writes its document to a file.
 * It starts no Spring context and opens no connection; everything it needs is on its command line,
 * so it holds none of the web server's secrets. {@link ProcessScanEngine} launches it, directly on
 * a plain classpath or through {@code DocsWatcherApplication.main} when the app runs from its jar.
 *
 * <p>Exit status: 0 with the document written to {@code --out}; 1 when the scan failed, with the
 * reason as the last line of standard error; 2 for a bad command line. Anything else is a crash.
 */
public final class ScanChild {

  /** The first argument that turns the application's main method into this one. */
  public static final String COMMAND = "__docswatcher-scan-child";

  static final int FAILED = 1;
  static final int USAGE = 2;

  private ScanChild() {}

  public static void main(String[] args) {
    Args a;
    try {
      a = Args.parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println("usage: " + e.getMessage());
      System.exit(USAGE);
      return;
    }
    try {
      Knowledge base = a.knowledge == null ? Knowledge.bundled() : Knowledge.load(a.knowledge);
      String json = ScanJob.run(base, a.repoRoot, a.ref, a.shared, a.ownRecords, a.limits, a.today);
      // Written beside the target and moved into place, so a half-written file is never read.
      Path partial = a.out.resolveSibling(a.out.getFileName() + ".partial");
      Files.writeString(partial, json, StandardCharsets.UTF_8);
      Files.move(partial, a.out);
    } catch (Throwable t) {
      t.printStackTrace();
      System.err.println(t);
      System.exit(FAILED);
      return;
    }
    System.exit(0);
  }

  /** The command line, built by {@link #arguments} and read back by {@link #parse}. */
  record Args(Path repoRoot, RepoRef ref, List<OwnKnowledge.Source> shared, boolean ownRecords, Path knowledge,
      ScanLimits limits, LocalDate today, Path out) {

    List<String> arguments() {
      List<String> out = new ArrayList<>(List.of(
          "--repo-root", repoRoot.toString(),
          "--own-records", Boolean.toString(ownRecords),
          "--max-files", Integer.toString(limits.maxFiles()),
          "--max-bytes", Long.toString(limits.maxBytes()),
          "--max-millis", Long.toString(limits.maxDuration().toMillis()),
          "--today", today.toString(),
          "--out", this.out.toString()));
      // A null part of the reference is left off rather than sent as an empty string.
      optional(out, "--host", ref.host());
      optional(out, "--owner", ref.owner());
      optional(out, "--name", ref.name());
      optional(out, "--ref", ref.ref());
      optional(out, "--sha", ref.sha());
      optional(out, "--knowledge", knowledge == null ? null : knowledge.toString());
      for (OwnKnowledge.Source s : shared) {
        out.addAll(List.of("--shared-label", s.label(), "--shared-dir", s.dir().toString()));
      }
      return out;
    }

    static Args parse(String[] args) {
      Path repoRoot = null, knowledge = null, out = null;
      String host = null, owner = null, name = null, ref = null, sha = null;
      boolean ownRecords = true;
      int maxFiles = ScanLimits.DEFAULT.maxFiles();
      long maxBytes = ScanLimits.DEFAULT.maxBytes();
      long maxMillis = ScanLimits.DEFAULT.maxDuration().toMillis();
      LocalDate today = null;
      List<String> labels = new ArrayList<>();
      List<Path> dirs = new ArrayList<>();
      for (int i = 0; i < args.length; i += 2) {
        if (i + 1 >= args.length) {
          throw new IllegalArgumentException(args[i] + " needs a value");
        }
        String v = args[i + 1];
        switch (args[i]) {
          case "--repo-root" -> repoRoot = Path.of(v);
          case "--host" -> host = v;
          case "--owner" -> owner = v;
          case "--name" -> name = v;
          case "--ref" -> ref = v;
          case "--sha" -> sha = v;
          case "--own-records" -> ownRecords = Boolean.parseBoolean(v);
          case "--knowledge" -> knowledge = Path.of(v);
          case "--max-files" -> maxFiles = Integer.parseInt(v);
          case "--max-bytes" -> maxBytes = Long.parseLong(v);
          case "--max-millis" -> maxMillis = Long.parseLong(v);
          case "--today" -> today = LocalDate.parse(v);
          case "--out" -> out = Path.of(v);
          case "--shared-label" -> labels.add(v);
          case "--shared-dir" -> dirs.add(Path.of(v));
          default -> throw new IllegalArgumentException("unknown option " + args[i]);
        }
      }
      if (repoRoot == null || out == null || today == null || labels.size() != dirs.size()) {
        throw new IllegalArgumentException("--repo-root, --out and --today are required, and each --shared-label needs a --shared-dir");
      }
      List<OwnKnowledge.Source> shared = new ArrayList<>();
      for (int i = 0; i < labels.size(); i++) {
        shared.add(new OwnKnowledge.Source(labels.get(i), dirs.get(i)));
      }
      return new Args(repoRoot, new RepoRef(host, owner, name, ref, sha), List.copyOf(shared), ownRecords, knowledge,
          new ScanLimits(maxFiles, maxBytes, Duration.ofMillis(maxMillis)), today, out);
    }

    private static void optional(List<String> out, String option, String value) {
      if (value != null) {
        out.addAll(List.of(option, value));
      }
    }
  }
}
