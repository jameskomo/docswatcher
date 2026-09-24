package dev.docswatcher.engine;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Runs the three detector layers over a checkout and emits an inventory document. */
public final class Engine {

  public static final String NAME = "docswatcher-engine-java";
  public static final String VERSION = "0.1.0";

  private final Knowledge knowledge;

  public Engine(Knowledge knowledge) {
    this.knowledge = knowledge;
  }

  public Knowledge knowledge() {
    return knowledge;
  }

  public Inventory scan(Path repoRoot, RepoRef repo) {
    return scan(repoRoot, repo, List.of());
  }

  /** As {@link #scan(Path, RepoRef)}, also excluding {@code exclude} (.gitignore syntax) for this run. */
  public Inventory scan(Path repoRoot, RepoRef repo, List<String> exclude) {
    return scan(repoRoot, repo, exclude, ScanLimits.DEFAULT);
  }

  /**
   * As {@link #scan(Path, RepoRef, List)} within {@code limits}. A scan a limit stopped still
   * returns what it found, and says so in {@code stats.incomplete}.
   */
  public Inventory scan(Path repoRoot, RepoRef repo, List<String> exclude, ScanLimits limits) {
    long start = System.nanoTime();
    long budget = limits.maxDuration().toNanos();
    BooleanSupplier expired = () -> System.nanoTime() - start > budget;
    FileTree tree = FileTree.read(repoRoot, exclude, limits, expired);
    Accumulator acc = new Accumulator();
    int unreached = scanFiles(tree.files, acc, expired);
    Inventory.Incomplete incomplete = tree.incomplete != null ? tree.incomplete
        : unreached > 0 ? new Inventory.Incomplete(ScanLimits.MAX_DURATION, limits.maxDuration().toMillis(), unreached)
        : null;
    long ms = (System.nanoTime() - start) / 1_000_000;
    return new Inventory(
        "1",
        repo,
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        new Inventory.EngineInfo(NAME, VERSION, knowledge.version()),
        new Inventory.Stats(tree.files.size(), tree.skipped, ms, List.of("manifest", "literal", "callsite"), incomplete),
        acc.finish());
  }

  /** The pure part: files in, contracts out. */
  List<Contract> scanFiles(List<SourceFile> files) {
    Accumulator acc = new Accumulator();
    scanFiles(files, acc, () -> false);
    return acc.finish();
  }

  /** Returns how many files the call-site layer left unreached because time ran out. */
  private int scanFiles(List<SourceFile> files, Accumulator acc, BooleanSupplier expired) {
    List<Provider> providers = knowledge.providers();
    ManifestLayer.run(providers, files, acc);
    LiteralLayer.run(providers, files, acc);
    return new CallsiteLayer().run(providers, files, acc, expired);
  }
}
