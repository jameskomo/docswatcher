package dev.docswatcher.cli;

import dev.docswatcher.engine.Change;
import dev.docswatcher.engine.Contract;
import dev.docswatcher.engine.Evidence;
import dev.docswatcher.engine.Finding;
import dev.docswatcher.engine.Inventory;
import dev.docswatcher.engine.Json;
import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.Matcher;
import dev.docswatcher.engine.Provider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "match", description = "Scan, then match against the knowledge base and print findings. Exit 1 if any breaking finding is open.")
final class MatchCommand implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "<path>", description = "Repository root.")
  Path path;

  @Mixin DocsWatcher.Common common;

  @Option(names = "--repo", paramLabel = "owner/name") String repo;
  @Option(names = "--ref") String ref;
  @Option(names = "--sha") String sha;

  @Option(names = "--format", paramLabel = "json|text", description = "Output format. Default: json.")
  String format = "json";

  @Option(names = "--include-low", description = "Include low-confidence contracts.")
  boolean includeLow;

  @Option(names = "--exclude", paramLabel = "<pattern>",
      description = "Skip paths matching this .gitignore-style pattern, after .gitignore files and .docswatcherignore. Repeatable.")
  List<String> exclude = new ArrayList<>();

  @Override
  public Integer call() {
    Knowledge k = common.loadKnowledge();
    Inventory inv = ScanCommand.scan(k, path, repo, ref, sha, exclude);
    List<Finding> findings = Matcher.match(inv, k, common.today, includeLow);
    if ("text".equals(format)) {
      System.out.print(render(inv, findings, k));
    } else {
      System.out.print(Json.write(findings));
    }
    return findings.stream().anyMatch(f -> "breaking".equals(f.severity())) ? 1 : 0;
  }

  static String render(Inventory inv, List<Finding> findings, Knowledge k) {
    Map<String, Change> changes = k.changes().stream().collect(Collectors.toMap(Change::id, c -> c));
    Map<String, Provider> providers = k.providers().stream().collect(Collectors.toMap(Provider::id, p -> p));
    Map<String, Contract> contracts = inv.contracts().stream().collect(Collectors.toMap(Contract::id, c -> c));
    long visible = inv.contracts().stream().filter(c -> !"low".equals(c.confidence())).count();
    StringBuilder sb = new StringBuilder();
    String repoName = inv.repo() == null ? "local" : inv.repo().fullName();
    sb.append(findings.isEmpty() ? "✓ " : "⚠ ").append("External API drift · ").append(repoName).append('\n');
    sb.append("Scanned ").append(inv.stats().filesScanned()).append(" files · ")
        .append(visible).append(" external contracts found · ")
        .append(findings.size()).append(findings.size() == 1 ? " needs" : " need").append(" attention\n");

    Map<String, List<Finding>> bySeverity = new LinkedHashMap<>();
    for (Finding f : findings) bySeverity.computeIfAbsent(f.severity(), x -> new ArrayList<>()).add(f);
    int n = 1;
    for (String sev : List.of("breaking", "warning", "info")) {
      List<Finding> group = bySeverity.get(sev);
      if (group == null) continue;
      sb.append('\n').append(switch (sev) {
        case "breaking" -> "Breaking · act before a date";
        case "warning" -> "Warning · behavior changed, no date";
        default -> "Informational";
      }).append("\n\n");
      for (Finding f : group) {
        Change ch = changes.get(f.change());
        Contract c = contracts.get(f.contract());
        Provider p = providers.get(c.provider());
        sb.append(n++).append(". ").append(p == null ? c.provider() : p.name()).append(" · ")
            .append(c.kind().replace('_', ' ')).append(" `").append(c.key()).append("`");
        if (f.effective() != null) {
          sb.append(" · ").append(ch.kind().equals("retired") ? "retired" : "sunset").append(' ').append(f.effective());
          if (f.daysRemaining() != null) {
            int d = f.daysRemaining();
            sb.append(d < 0 ? " (" + (-d) + " days ago)" : " (in " + d + " days)");
          }
        }
        sb.append('\n');
        sb.append("   ").append(ch.title()).append('\n');
        sb.append("   Referenced in: ").append(c.evidence().stream()
            .map(e -> e.path() + ":" + e.line()).collect(Collectors.joining(", "))).append('\n');
        if (ch.migration() != null) {
          if (ch.migration().replacement() != null) {
            sb.append("   Provider guidance: migrate to ").append(ch.migration().replacement());
            if (ch.migration().effort() != null) sb.append(" (").append(ch.migration().effort()).append(" effort)");
            sb.append('\n');
          }
          if (ch.migration().guide() != null) sb.append("   → ").append(ch.migration().guide()).append('\n');
        }
        sb.append('\n');
      }
    }
    List<String> healthy = inv.contracts().stream()
        .filter(c -> !"low".equals(c.confidence()))
        .filter(c -> findings.stream().noneMatch(f -> f.contract().equals(c.id())))
        .map(Contract::key).toList();
    sb.append("Healthy · ").append(healthy.size()).append(" contracts, nothing pending\n");
    if (!healthy.isEmpty()) {
      sb.append(healthy.stream().limit(8).collect(Collectors.joining(" · ")));
      if (healthy.size() > 8) sb.append(" · …");
      sb.append('\n');
    }
    sb.append("\nKnowledge base ").append(k.version()).append(" · ").append(k.changes().size()).append(" change records · ")
        .append(k.providers().size()).append(" providers\n");
    return sb.toString();
  }

  @SuppressWarnings("unused")
  private static String first(List<Evidence> ev) {
    return ev.isEmpty() ? "" : ev.get(0).path() + ":" + ev.get(0).line();
  }
}
