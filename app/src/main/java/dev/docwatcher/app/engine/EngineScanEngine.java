package dev.docwatcher.app.engine;

import dev.docwatcher.app.config.AppProperties;
import dev.docwatcher.app.model.ChangeDoc;
import dev.docwatcher.app.model.FindingDoc;
import dev.docwatcher.app.model.InventoryDoc;
import dev.docwatcher.app.model.RepoRefDoc;
import dev.docwatcher.engine.Engine;
import dev.docwatcher.engine.Inventory;
import dev.docwatcher.engine.Json;
import dev.docwatcher.engine.Knowledge;
import dev.docwatcher.engine.Matcher;
import dev.docwatcher.engine.RepoRef;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapter over the engine library. Engine objects never leave this class: results are
 * serialised with the engine's own JSON writer and parsed into the app's schema records,
 * so the seam is the inventory document exactly as the architecture doc describes.
 */
@Component
@ConditionalOnProperty(name = "docwatcher.engine.enabled", havingValue = "true", matchIfMissing = true)
public class EngineScanEngine implements ScanEngine {

  private final Knowledge knowledge;
  private final Engine engine;
  private final ObjectMapper mapper;
  private final List<ChangeDoc> changes;

  public EngineScanEngine(AppProperties properties, ObjectMapper mapper) {
    String dir = properties.knowledge().dir();
    this.knowledge = dir == null || dir.isBlank() ? Knowledge.bundled() : Knowledge.load(Path.of(dir));
    this.engine = new Engine(knowledge);
    this.mapper = mapper;
    this.changes = List.of(mapper.readValue(Json.write(knowledge.changes()), ChangeDoc[].class));
  }

  @Override
  public InventoryDoc scan(Path repoRoot, RepoRefDoc repo) {
    RepoRef ref = new RepoRef(repo.host(), repo.owner(), repo.name(), repo.ref(), repo.sha());
    Inventory inventory = engine.scan(repoRoot, ref);
    return mapper.readValue(Json.write(inventory), InventoryDoc.class);
  }

  @Override
  public List<FindingDoc> match(InventoryDoc inventory) {
    Inventory engineInventory = Json.read(mapper.writeValueAsString(inventory), Inventory.class);
    var findings = Matcher.match(engineInventory, knowledge, LocalDate.now(), false);
    return List.of(mapper.readValue(Json.write(findings), FindingDoc[].class));
  }

  @Override
  public String engineVersion() {
    return Engine.class.getPackage().getImplementationVersion() == null
        ? "dev"
        : Engine.class.getPackage().getImplementationVersion();
  }

  @Override
  public String knowledgeVersion() {
    return knowledge.version();
  }

  @Override
  public List<ChangeDoc> changes() {
    return changes;
  }
}
