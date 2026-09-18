package dev.docwatcher.cli;

import dev.docwatcher.engine.Contract;
import dev.docwatcher.engine.Engine;
import dev.docwatcher.engine.Finding;
import dev.docwatcher.engine.Inventory;
import dev.docwatcher.engine.Json;
import dev.docwatcher.engine.Knowledge;
import dev.docwatcher.engine.Matcher;
import dev.docwatcher.engine.RepoRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "scan", description = "Scan a checkout and print the inventory document.")
final class ScanCommand implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "<path>", description = "Repository root.")
  Path path;

  @Mixin DocWatcher.Common common;

  @Option(names = "--repo", paramLabel = "owner/name", description = "Repository name to record in the inventory.")
  String repo;

  @Option(names = "--ref", description = "Git ref to record.")
  String ref;

  @Option(names = "--sha", description = "Commit SHA to record.")
  String sha;

  @Option(names = "--write-expected", description = "Write expected-inventory.json and expected-findings.json next to a fixture's repo/ directory.")
  boolean writeExpected;

  @Option(names = "--include-low", description = "Include low-confidence contracts when writing expected findings.")
  boolean includeLow;

  @Override
  public Integer call() throws Exception {
    Knowledge k = common.loadKnowledge();
    Inventory inv = scan(k, path, repo, ref, sha);
    if (writeExpected) {
      Path fixtureDir = path.toAbsolutePath().normalize().getParent();
      Files.writeString(fixtureDir.resolve("expected-inventory.json"), Json.write(inv.contracts()));
      List<Finding.Pair> pairs = Matcher.match(inv, k, common.today, includeLow).stream().map(Finding::pair).toList();
      Files.writeString(fixtureDir.resolve("expected-findings.json"), Json.write(pairs));
      System.out.println("Wrote expected-inventory.json (" + inv.contracts().size() + " contracts) and expected-findings.json ("
          + pairs.size() + " findings) to " + fixtureDir);
      return 0;
    }
    System.out.print(Json.write(inv));
    return 0;
  }

  static Inventory scan(Knowledge k, Path path, String repo, String ref, String sha) {
    if (!Files.isDirectory(path)) throw new IllegalArgumentException("Not a directory: " + path);
    RepoRef r;
    if (repo != null && repo.contains("/")) {
      String[] parts = repo.split("/", 2);
      r = new RepoRef("github", parts[0], parts[1], ref, sha);
    } else {
      r = new RepoRef("local", null, repo != null ? repo : path.toAbsolutePath().normalize().getFileName().toString(), ref, sha);
    }
    return new Engine(k).scan(path, r);
  }

  static long countHigh(List<Contract> contracts) {
    return contracts.stream().filter(c -> !"low".equals(c.confidence())).count();
  }
}
