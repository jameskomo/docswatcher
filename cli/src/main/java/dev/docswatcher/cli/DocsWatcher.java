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
    version = "docswatcher " + DocsWatcher.VERSION,
    description = "Scan a repository for external API contracts and match them against provider deprecations.",
    subcommands = {ScanCommand.class, MatchCommand.class, ValidateCommand.class, McpCommand.class})
public final class DocsWatcher implements Callable<Integer> {

  /** The release this build is. The release workflow refuses to publish a binary that disagrees with its tag. */
  static final String VERSION = "0.2.0";

  /**
   * Exit code for a scan that did not complete. It must differ from 1, which `match` uses to say a
   * breaking finding is open: a crash that exited 1 read as "findings" to a script and, once its
   * empty output was counted, as "nothing found" to the GitHub Action.
   */
  static final int FAILED = 3;

  public static void main(String[] args) {
    int code;
    try {
      code = commandLine().execute(args);
    } catch (Throwable t) {
      // Errors such as OutOfMemoryError are not Exceptions, so picocli lets them through.
      System.err.println("docswatcher: " + t);
      code = FAILED;
    }
    System.exit(code);
  }

  /** The command line as main runs it: any exception from a command exits FAILED, not 1. */
  static CommandLine commandLine() {
    return new CommandLine(new DocsWatcher())
        .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
          cmd.getErr().println("docswatcher: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
          return FAILED;
        });
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
