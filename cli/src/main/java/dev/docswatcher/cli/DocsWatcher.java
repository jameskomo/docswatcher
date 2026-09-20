package dev.docswatcher.cli;

import dev.docswatcher.engine.Knowledge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** DocsWatcher command line. Finds the external API contracts a repository depends on. */
@Command(
    name = "docswatcher",
    mixinStandardHelpOptions = true,
    version = "docswatcher 0.1.3",
    description = "Scan a repository for external API contracts and match them against provider deprecations.",
    subcommands = {ScanCommand.class, MatchCommand.class, ValidateCommand.class})
public final class DocsWatcher implements Callable<Integer> {

  public static void main(String[] args) {
    System.exit(new CommandLine(new DocsWatcher()).execute(args));
  }

  @Override
  public Integer call() {
    CommandLine.usage(this, System.out);
    return 0;
  }

  /** Options shared by every command. */
  static class Common {
    @Option(names = "--knowledge", paramLabel = "<dir>", description = "Knowledge directory instead of the bundled release.")
    Path knowledge;

    @Option(names = "--today", paramLabel = "YYYY-MM-DD", description = "Date used for day counts and validation. Default: today.")
    LocalDate today = LocalDate.now();

    Knowledge loadKnowledge() {
      if (knowledge != null) return Knowledge.load(knowledge);
      Path local = Path.of("knowledge");
      if (Files.isDirectory(local.resolve("providers"))) {
        return Knowledge.load(local);
      }
      return Knowledge.bundled();
    }
  }
}
