package dev.docswatcher.app.support;

import dev.docswatcher.app.engine.ScanEngine;
import dev.docswatcher.app.model.ChangeDoc;
import dev.docswatcher.app.model.ContractDoc;
import dev.docswatcher.app.model.EvidenceDoc;
import dev.docswatcher.app.model.FindingDoc;
import dev.docswatcher.app.model.InventoryDoc;
import dev.docswatcher.app.model.ProviderDoc;
import dev.docswatcher.app.model.RepoRefDoc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic stand-in for the engine. It "detects" a gpt-4-turbo model contract when the
 * checkout contains a file named models.yaml with that string, and matches it to one change.
 */
public class FakeScanEngine implements ScanEngine {

  public static final String CHANGE_ID = "openai-gpt-4-turbo-shutdown-2026";
  public static final String CONTRACT_ID = "openai:model:gpt-4-turbo";
  public static final LocalDate EFFECTIVE = LocalDate.of(2026, 10, 23);

  public int scans = 0;
  /** Set to make the next scans report that a whole-scan limit stopped them. */
  public InventoryDoc.Incomplete incomplete = null;

  @Override
  public InventoryDoc scan(Path repoRoot, RepoRefDoc repo) {
    scans++;
    List<ContractDoc> contracts = new ArrayList<>();
    Path models = repoRoot.resolve("models.yaml");
    try {
      if (Files.exists(models) && Files.readString(models).contains("gpt-4-turbo")) {
        contracts.add(new ContractDoc(CONTRACT_ID, "openai", "model", "gpt-4-turbo", "medium",
            List.of(new EvidenceDoc("models.yaml", 1, 8, "model: gpt-4-turbo", "openai.literal.model-gpt", "literal")), null));
      }
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return new InventoryDoc("1", repo, "2026-09-18T00:00:00Z", new InventoryDoc.EngineInfo("fake", "0", "test"),
        new InventoryDoc.Stats(1, 0, 5, List.of("literal"), incomplete), contracts);
  }

  @Override
  public List<FindingDoc> match(InventoryDoc inventory) {
    List<FindingDoc> out = new ArrayList<>();
    for (ContractDoc c : inventory.contracts()) {
      if (c.id().equals(CONTRACT_ID)) {
        int days = (int) ChronoUnit.DAYS.between(LocalDate.now(), EFFECTIVE);
        out.add(new FindingDoc(inventory.repo().owner() + "/" + inventory.repo().name() + ":" + CHANGE_ID + ":" + CONTRACT_ID,
            CONTRACT_ID, CHANGE_ID, "breaking", EFFECTIVE, days, c.evidence(), "open", null, null));
      }
    }
    return out;
  }

  @Override
  public String engineVersion() {
    return "0-test";
  }

  @Override
  public String knowledgeVersion() {
    return "test";
  }

  @Override
  public List<ProviderDoc> providers() {
    return List.of(
        new ProviderDoc("openai", "OpenAI", List.of("https://api.openai.com")),
        new ProviderDoc("slack", "Slack", List.of("https://slack.com/api")),
        new ProviderDoc("shopify", "Shopify", List.of("https://{shop}.myshopify.com/admin/api")),
        new ProviderDoc("aws", "AWS SDK", List.of("https://amazonaws.com")),
        new ProviderDoc("twilio", "Twilio", List.of("https://api.twilio.com", "https://notify.twilio.com")));
  }

  @Override
  public List<ChangeDoc> changes() {
    return List.of(new ChangeDoc(CHANGE_ID, "openai", "retired", "breaking", "gpt-4-turbo shut down", "Requests fail after shutdown.",
        LocalDate.of(2026, 4, 22), EFFECTIVE, new ChangeDoc.Migration("gpt-5.6-sol", "https://developers.openai.com/api/docs/deprecations", "small", "Replace the model ID."), "active"));
  }
}
