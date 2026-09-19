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
}
