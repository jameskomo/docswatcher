package dev.docswatcher.app.engine;

import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.model.InventoryDoc;
import dev.docswatcher.app.model.ProviderDoc;
import dev.docswatcher.app.model.RepoRefDoc;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The app's only door to the engine. Everything crosses it as schema documents. */
public interface ScanEngine {

  InventoryDoc scan(Path repoRoot, RepoRefDoc repo);

  List<FindingDoc> match(InventoryDoc inventory);

  String engineVersion();

  String knowledgeVersion();

  List<ChangeDoc> changes();

  /**
   * Provider profiles, used to recognise a provider from a hostname observed in
   * telemetry. Reading base URLs from the knowledge base keeps runtime attribution
   * on the same data as detection, so adding a provider teaches both at once.
   */
  List<ProviderDoc> providers();

  default Optional<ChangeDoc> change(String id) {
    return changes().stream().filter(c -> c.id().equals(id)).findFirst();
  }

  /**
   * A directory of a team's own API records (docs/19-your-own-apis.md), such as a checkout of the
   * organisation's .docswatcher repository, and how problems with it are named.
   */
  record OwnRecords(String label, Path dir) {}

  /**
   * A scan and its findings with own API records added.
   *
   * @param providers the own provider ids used; empty when there were none or they were invalid
   * @param changes the own change records used, for issue text
   * @param problems every error that kept the own records out of this scan; the scan then used the
   *     bundled knowledge alone, and callers must report these
   * @param warnings problems that did not keep them out
   */
  record OwnScan(InventoryDoc inventory, List<FindingDoc> findings, List<String> providers, List<ChangeDoc> changes,
      List<String> problems, List<String> warnings) {

    public Optional<ChangeDoc> change(String id) {
      return changes.stream().filter(c -> c.id().equals(id)).findFirst();
    }
  }

  /**
   * Scans with the checkout's own .docswatcher/ records and {@code shared} added. Invalid records
   * are left out and returned as problems; the scan itself still happens.
   */
  default OwnScan scanWithOwnRecords(Path repoRoot, RepoRefDoc repo, List<OwnRecords> shared) {
    InventoryDoc inventory = scan(repoRoot, repo);
    return new OwnScan(inventory, match(inventory), List.of(), List.of(), List.of(), List.of());
  }
}
