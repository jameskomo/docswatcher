package dev.docwatcher.cli;

import dev.docwatcher.engine.Knowledge;
import dev.docwatcher.engine.Validator;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "validate", description = "Validate a knowledge directory. Exit 1 on any error.")
final class ValidateCommand implements Callable<Integer> {

  @Parameters(index = "0", arity = "0..1", paramLabel = "<dir>", description = "Knowledge directory. Default: --knowledge, ./knowledge, or bundled.")
  Path dir;

  @Mixin DocWatcher.Common common;

  @Override
  public Integer call() {
    Knowledge k = dir != null ? Knowledge.load(dir) : common.loadKnowledge();
    Validator.Report r = Validator.validate(k, common.today);
    System.out.println("Knowledge " + k.version() + ": " + k.providers().size() + " providers, "
        + k.changes().size() + " change records, " + k.fixtures().size() + " fixtures");
    for (String w : r.warnings()) System.out.println("warning: " + w);
    for (String e : r.errors()) System.out.println("error: " + e);
    System.out.println(r.ok()
        ? "OK · 0 errors, " + r.warnings().size() + " warnings"
        : "FAILED · " + r.errors().size() + " errors, " + r.warnings().size() + " warnings");
    return r.ok() ? 0 : 1;
  }
}
