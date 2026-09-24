package dev.docswatcher.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A team's own API records, added to the bundled knowledge for one scan (docs/19-your-own-apis.md,
 * ADR 0008). They use the knowledge base's own layout, {@code providers/<id>/provider.yaml},
 * {@code detectors.yaml} and {@code changes/*.yaml}, and come from a repository's
 * {@code .docswatcher/} directory or from a shared directory such as a checkout of an
 * organisation's {@code .docswatcher} repository.
 *
 * <p>Records are validated by {@link Validator#validateOwn} before they are used. Any error means
 * none of them are: the result then carries the bundled knowledge and the errors, and every caller
 * reports those errors. Mirrored by web/engine/own.ts, held to the same messages by
 * engine/src/test/resources/own-knowledge-cases.json.
 */
public final class OwnKnowledge {

  /** The directory at a repository's root that holds its own records. */
  public static final String DIR = ".docswatcher";

  /** Every provider id of a team's own records starts with this, and no bundled one does. */
  public static final String PREFIX = "internal-";

  private OwnKnowledge() {}

  /**
   * Where records are read from.
   *
   * @param label how messages name the directory, e.g. {@code .docswatcher}
   * @param dir a directory holding {@code providers/}
   */
  public record Source(String label, Path dir) {}

  /**
   * The outcome of adding own records to a knowledge base.
   *
   * @param knowledge the merged knowledge when there are no errors, otherwise the base unchanged
   * @param providers the own providers read, whether or not they were valid
   * @param changes the own change records read, whether or not they were valid
   */
  public record Result(Knowledge knowledge, List<Provider> providers, List<Change> changes, List<String> errors, List<String> warnings) {
    public boolean ok() {
      return errors.isEmpty();
    }

    /** One line for a report: what was added, or why nothing was. */
    public String summary() {
      if (!ok()) {
        return "Your own API records were not used: " + errors.size() + (errors.size() == 1 ? " error" : " errors");
      }
      return "Your own API records: " + providers.size() + (providers.size() == 1 ? " provider, " : " providers, ")
          + changes.size() + (changes.size() == 1 ? " change record" : " change records");
    }
  }

  /** The repository's own records directory, when it has one. */
  public static Optional<Source> inRepo(Path repoRoot) {
    Path dir = repoRoot.resolve(DIR);
    return Files.isDirectory(dir) ? Optional.of(new Source(DIR, dir)) : Optional.empty();
  }

  /**
   * What a scan of {@code repoRoot} reads: the repository's own {@code .docswatcher/}, then each
   * shared directory, every directory once however it was named.
   */
  public static List<Source> sources(Path repoRoot, List<Source> shared) {
    List<Source> out = new ArrayList<>();
    Set<Path> seen = new HashSet<>();
    if (repoRoot != null) inRepo(repoRoot).ifPresent(s -> {
      seen.add(identity(s.dir()));
      out.add(s);
    });
    for (Source s : shared) {
      if (seen.add(identity(s.dir()))) out.add(s);
    }
    return out;
  }

  private static Path identity(Path dir) {
    try {
      return dir.toRealPath();
    } catch (IOException e) {
      return dir.toAbsolutePath().normalize();
    }
  }

  /** The base knowledge plus the records of every source, or the base and the errors. */
  public static Result merge(Knowledge base, List<Source> sources, LocalDate today) {
    if (sources.isEmpty()) return new Result(base, List.of(), List.of(), List.of(), List.of());
    List<Provider> providers = new ArrayList<>();
    List<Change> changes = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (Source s : sources) load(s, providers, changes, errors);
    Validator.Report report = Validator.validateOwn(base, providers, changes, today);
    errors.addAll(report.errors());
    Knowledge merged = errors.isEmpty() ? base.with(providers, changes) : base;
    return new Result(merged, List.copyOf(providers), List.copyOf(changes), List.copyOf(errors), report.warnings());
  }

  private static void load(Source s, List<Provider> providers, List<Change> changes, List<String> errors) {
    Path providersDir = s.dir().resolve("providers");
    if (!Files.isDirectory(providersDir)) {
      errors.add(s.label() + ": no providers/ directory");
      return;
    }
    for (Path pdir : list(providersDir, true)) {
      String name = pdir.getFileName().toString();
      String where = s.label() + "/providers/" + name;
      Path profileFile = pdir.resolve("provider.yaml");
      if (!Files.isRegularFile(profileFile)) {
        errors.add(where + ": provider.yaml missing");
        continue;
      }
      JsonNode profile = read(profileFile, where + "/provider.yaml", errors);
      if (profile == null) continue;
      JsonNode detectors = null;
      Path detectorsFile = pdir.resolve("detectors.yaml");
      if (Files.isRegularFile(detectorsFile)) {
        detectors = read(detectorsFile, where + "/detectors.yaml", errors);
        if (detectors == null) continue;
      }
      Provider p = Loaders.provider(profile, detectors);
      if (!name.equals(p.id())) errors.add(where + "/provider.yaml: id " + p.id() + " does not match its directory " + name);
      providers.add(p);
      Path changesDir = pdir.resolve("changes");
      if (!Files.isDirectory(changesDir)) continue;
      for (Path cf : list(changesDir, false)) {
        String file = where + "/changes/" + cf.getFileName();
        if (file.endsWith(".yml")) {
          errors.add(file + ": name it .yaml, the only extension that is read");
          continue;
        }
        if (!file.endsWith(".yaml")) continue;
        JsonNode n = read(cf, file, errors);
        if (n != null) changes.add(Loaders.change(n, file));
      }
    }
  }

  /** A YAML mapping, or null with the reason added to {@code errors}. */
  private static JsonNode read(Path file, String what, List<String> errors) {
    JsonNode n;
    try {
      n = Yaml.read(Files.newInputStream(file), what);
    } catch (IOException | UncheckedIOException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      String detail = cause.getMessage() == null ? cause.toString() : cause.getMessage();
      errors.add(what + ": not valid YAML: " + detail.lines().findFirst().orElse("").strip());
      return null;
    }
    if (!n.isObject()) {
      errors.add(what + ": expected a mapping of fields");
      return null;
    }
    return n;
  }

  private static List<Path> list(Path dir, boolean dirs) {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> dirs ? Files.isDirectory(p) : Files.isRegularFile(p))
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list " + dir, e);
    }
  }
}
