package dev.docwatcher.engine;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Runs the three detector layers over a checkout and emits an inventory document. */
public final class Engine {

  public static final String NAME = "docwatcher-engine-java";
  public static final String VERSION = "0.1.0";

  private final Knowledge knowledge;

  public Engine(Knowledge knowledge) {
    this.knowledge = knowledge;
  }

  public Knowledge knowledge() {
    return knowledge;
  }

  public Inventory scan(Path repoRoot, RepoRef repo) {
    long start = System.nanoTime();
    FileTree tree = FileTree.read(repoRoot);
    List<Contract> contracts = scanFiles(tree.files);
    long ms = (System.nanoTime() - start) / 1_000_000;
    return new Inventory(
        "1",
        repo,
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        new Inventory.EngineInfo(NAME, VERSION, knowledge.version()),
        new Inventory.Stats(tree.files.size(), tree.skipped, ms, List.of("manifest", "literal", "callsite")),
        contracts);
  }

  /** The pure part: files in, contracts out. */
  List<Contract> scanFiles(List<SourceFile> files) {
    Accumulator acc = new Accumulator();
    List<Provider> providers = knowledge.providers();
    ManifestLayer.run(providers, files, acc);
    LiteralLayer.run(providers, files, acc);
    new CallsiteLayer().run(providers, files, acc);
    return acc.finish();
  }
}
