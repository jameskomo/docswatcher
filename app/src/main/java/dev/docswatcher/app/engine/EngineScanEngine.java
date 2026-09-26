package dev.docswatcher.app.engine;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.config.ScanLimitsProperties;
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
import dev.docswatcher.engine.OwnKnowledge;
import dev.docswatcher.engine.RepoRef;
import dev.docswatcher.engine.ScanLimits;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapter over the engine library. Engine objects never leave this class: results are
 * serialised with the engine's own JSON writer and parsed into the app's schema records,
 * so the seam is the inventory document exactly as the architecture doc describes.
 *
 * <p>Scans run in the calling JVM. {@link ProcessScanEngine} runs the same {@link ScanJob} in a
 * process of its own instead; {@link ScanEngineConfig} picks one.
 */
public class EngineScanEngine implements ScanEngine {

  private final Knowledge knowledge;
  private final Path knowledgeDir;
  private final ObjectMapper mapper;
  private final ScanLimits limits;
  private final List<ChangeDoc> changes;
  private final List<ProviderDoc> providers;

  public EngineScanEngine(AppProperties properties, ObjectMapper mapper) {
    this(properties, mapper, ScanLimits.DEFAULT);
  }

  public EngineScanEngine(AppProperties properties, ObjectMapper mapper, ScanLimitsProperties limits) {
    this(properties, mapper, limits.limits());
  }

  public EngineScanEngine(AppProperties properties, ObjectMapper mapper, ScanLimits limits) {
    this.limits = limits;
    String dir = properties.knowledge().dir();
    this.knowledgeDir = dir == null || dir.isBlank() ? null : Path.of(dir);
    this.knowledge = knowledgeDir == null ? Knowledge.bundled() : Knowledge.load(knowledgeDir);
    this.mapper = mapper;
    this.changes = List.of(mapper.readValue(Json.write(knowledge.changes()), ChangeDoc[].class));
    this.providers = List.of(mapper.readValue(Json.write(knowledge.providers()), ProviderDoc[].class));
  }

  @Override
  public InventoryDoc scan(Path repoRoot, RepoRefDoc repo) {
    return read(ScanJob.run(knowledge, repoRoot, ref(repo), List.of(), false, limits, LocalDate.now())).inventory();
  }

  @Override
  public List<FindingDoc> match(InventoryDoc inventory) {
    Inventory engineInventory = Json.read(mapper.writeValueAsString(inventory), Inventory.class);
    var findings = Matcher.match(engineInventory, knowledge, LocalDate.now(), false);
    return List.of(mapper.readValue(Json.write(findings), FindingDoc[].class));
  }

  @Override
  public OwnScan scanWithOwnRecords(Path repoRoot, RepoRefDoc repo, List<OwnRecords> shared) {
    return read(ScanJob.run(knowledge, repoRoot, ref(repo), sources(shared), true, limits, LocalDate.now()));
  }

  /** The knowledge directory this engine was built from, or null for the bundled knowledge. */
  public Path knowledgeDir() {
    return knowledgeDir;
  }

  public ScanLimits limits() {
    return limits;
  }

  /** Parses a {@link ScanJob} document, wherever it was produced, into the app's records. */
  public OwnScan read(String scanJobJson) {
    return mapper.readValue(scanJobJson, OwnScan.class);
  }

  static RepoRef ref(RepoRefDoc repo) {
    return new RepoRef(repo.host(), repo.owner(), repo.name(), repo.ref(), repo.sha());
  }

  static List<OwnKnowledge.Source> sources(List<OwnRecords> shared) {
    return shared.stream().map(s -> new OwnKnowledge.Source(s.label(), s.dir())).toList();
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
