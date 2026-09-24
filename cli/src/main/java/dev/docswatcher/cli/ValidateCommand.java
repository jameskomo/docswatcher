package dev.docswatcher.cli;

import dev.docswatcher.engine.Knowledge;
import dev.docswatcher.engine.OwnKnowledge;
import dev.docswatcher.engine.Validator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Command(name = "validate",
    description = {
        "Validate a knowledge directory, or your own API records. Exit 1 on any error.",
        "Your own records are a directory named .docswatcher, every --knowledge-extra, or, with no <dir>, "
            + "./.docswatcher. They are checked against the knowledge base they will be added to."})
final class ValidateCommand implements Callable<Integer> {

  @Parameters(index = "0", arity = "0..1", paramLabel = "<dir>",
      description = "Knowledge directory, or a .docswatcher directory of your own records. Default: your own records "
          + "when there are any, else --knowledge, ./knowledge, or bundled.")
  Path dir;

  @Mixin DocsWatcher.Common common;

  @Override
  public Integer call() {
    List<OwnKnowledge.Source> own = ownSources();
    Knowledge k = dir != null && !isOwnDir(dir) ? Knowledge.load(dir) : common.loadKnowledge();
    if (!own.isEmpty()) return validateOwn(k, own);
    Validator.Report r = Validator.validate(k, common.today);
    System.out.println("Knowledge " + k.version() + ": " + k.providers().size() + " providers, "
        + k.changes().size() + " change records, " + k.fixtures().size() + " fixtures");
    return print(r.errors(), r.warnings());
  }

  /** Own records to validate: a .docswatcher directory given as <dir>, or with none, ./.docswatcher, then --knowledge-extra. */
  private List<OwnKnowledge.Source> ownSources() {
    if (dir == null) return OwnKnowledge.sources(Path.of(""), common.shared());
    if (!isOwnDir(dir)) return common.shared();
    List<OwnKnowledge.Source> out = new ArrayList<>();
    out.add(new OwnKnowledge.Source(dir.toString().replace('\\', '/'), dir));
    out.addAll(common.shared());
    return OwnKnowledge.sources(null, out);
  }

  private static boolean isOwnDir(Path dir) {
    Path name = dir.toAbsolutePath().normalize().getFileName();
    return name != null && name.toString().equals(OwnKnowledge.DIR);
  }

  private int validateOwn(Knowledge base, List<OwnKnowledge.Source> sources) {
    OwnKnowledge.Result r = OwnKnowledge.merge(base, sources, common.today);
    System.out.println("Your own API records from " + String.join(", ", sources.stream().map(OwnKnowledge.Source::label).toList())
        + ": " + r.providers().size() + (r.providers().size() == 1 ? " provider, " : " providers, ")
        + r.changes().size() + (r.changes().size() == 1 ? " change record" : " change records")
        + ", checked against knowledge base " + base.version());
    return print(r.errors(), r.warnings());
  }

  private static int print(List<String> errors, List<String> warnings) {
    for (String w : warnings) System.out.println("warning: " + w);
    for (String e : errors) System.out.println("error: " + e);
    System.out.println(errors.isEmpty()
        ? "OK · 0 errors, " + warnings.size() + " warnings"
        : "FAILED · " + errors.size() + " errors, " + warnings.size() + " warnings");
    return errors.isEmpty() ? 0 : 1;
  }
}
