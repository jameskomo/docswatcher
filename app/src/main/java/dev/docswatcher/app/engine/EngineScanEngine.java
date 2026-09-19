package dev.docswatcher.app.engine;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.model.InventoryDoc;
import dev.docswatcher.app.model.ProviderDoc;
import dev.docswatcher.app.model.RepoRefDoc;
import dev.docswatcher.engine.Engine;
import dev.docswatcher.engine.Inventory;
import dev.docswatcher.engine.Json;
import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.Matcher;
import dev.docswatcher.engine.RepoRef;
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
@ConditionalOnProperty(name = "docswatcher.engine.enabled", havingValue = "true", matchIfMissing = true)
public class EngineScanEngine implements ScanEngine {

  private final Knowledge knowledge;
  private final Engine engine;
  private final ObjectMapper mapper;
  private final List<ChangeDoc> changes;
  private final List<ProviderDoc> providers;

  public EngineScanEngine(AppProperties properties, ObjectMapper mapper) {
    String dir = properties.knowledge().dir();
    this.knowledge = dir == null || dir.isBlank() ? Knowledge.bundled() : Knowledge.load(Path.of(dir));
    this.engine = new Engine(knowledge);
    this.mapper = mapper;
    this.changes = List.of(mapper.readValue(Json.write(knowledge.changes()), ChangeDoc[].class));
    this.providers = List.of(mapper.readValue(Json.write(knowledge.providers()), ProviderDoc[].class));
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

  @Override
  public List<ProviderDoc> providers() {
    return providers;
  }
}
