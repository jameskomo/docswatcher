package dev.docwatcher.app.engine;

import dev.docwatcher.app.model.ChangeDoc;
import dev.docwatcher.app.model.FindingDoc;
import dev.docwatcher.app.model.InventoryDoc;
import dev.docwatcher.app.model.RepoRefDoc;
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

  default Optional<ChangeDoc> change(String id) {
    return changes().stream().filter(c -> c.id().equals(id)).findFirst();
  }
}
