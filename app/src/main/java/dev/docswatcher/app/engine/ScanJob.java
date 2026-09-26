package dev.docswatcher.app.engine;

import dev.docswatcher.engine.Change;
import dev.docswatcher.engine.Engine;
import dev.docswatcher.engine.Finding;
import dev.docswatcher.engine.Inventory;
import dev.docswatcher.engine.Json;
import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.Matcher;
import dev.docswatcher.engine.OwnKnowledge;
import dev.docswatcher.engine.Provider;
import dev.docswatcher.engine.RepoRef;
import dev.docswatcher.engine.ScanLimits;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * One scan of one checkout, from files on disk to a JSON document shaped like
 * {@link ScanEngine.OwnScan}. It is the only code that reads a checkout, and it runs unchanged in
 * the web server ({@link EngineScanEngine}) or in a scan process of its own ({@link ScanChild}),
 * so the two isolation modes cannot drift apart: both parse the same bytes.
 */
public final class ScanJob {

  private ScanJob() {}

  /**
   * @param base the bundled knowledge, or the configured knowledge directory
   * @param shared own API records from outside the checkout, such as the organisation's
   * @param ownRecords false to scan with {@code base} alone and skip matching, as {@link ScanEngine#scan} does
   */
  public static String run(Knowledge base, Path repoRoot, RepoRef ref, List<OwnKnowledge.Source> shared,
      boolean ownRecords, ScanLimits limits, LocalDate today) {
    if (!ownRecords) {
      Inventory inventory = new Engine(base).scan(repoRoot, ref, List.of(), limits);
      return Json.write(new Output(inventory, List.of(), List.of(), List.of(), List.of(), List.of()));
    }
    OwnKnowledge.Result own = OwnKnowledge.merge(base, OwnKnowledge.sources(repoRoot, shared), today);
    Knowledge k = own.knowledge();
    Inventory inventory = new Engine(k).scan(repoRoot, ref, List.of(), limits);
    List<Finding> findings = Matcher.match(inventory, k, today, false);
    return Json.write(new Output(inventory, findings,
        own.ok() ? own.providers().stream().map(Provider::id).toList() : List.of(),
        own.ok() ? own.changes() : List.of(),
        own.errors(), own.warnings()));
  }

  /** The engine-side twin of {@link ScanEngine.OwnScan}: same names, so one parses as the other. */
  public record Output(Inventory inventory, List<Finding> findings, List<String> providers, List<Change> changes,
      List<String> problems, List<String> warnings) {}
}
