package dev.docswatcher.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The loaded knowledge base: providers with detector tables, change records, and fixtures.
 * Load from a directory on disk or from the bundled classpath copy.
 */
public final class Knowledge {

  private static final String RESOURCE_ROOT = "docswatcher-knowledge";

  /**
   * The file at the knowledge root that holds the knowledge base version, e.g. 2026.09.24. It is
   * the only source of that version: the build copies it into the jar unchanged.
   */
  public static final String VERSION_FILE = "VERSION";

  /** Reported only for a knowledge directory with no VERSION file. The web build says the same. */
  public static final String UNVERSIONED = "unversioned";

  private final String version;
  private final List<Provider> providers;
  private final List<Change> changes;
  private final List<Fixture> fixtures;
  private final Path root;

  private Knowledge(String version, List<Provider> providers, List<Change> changes, List<Fixture> fixtures, Path root) {
    this.version = version;
    this.providers = List.copyOf(providers);
    this.changes = List.copyOf(changes);
    this.fixtures = List.copyOf(fixtures);
    this.root = root;
  }

  /**
   * Load from a knowledge directory containing providers/ and fixtures/. The version is the
   * directory's VERSION file, the same file the bundled copy and the web build read, so every
   * surface names the same knowledge base the same way.
   */
  public static Knowledge load(Path dir) {
    if (!Files.isDirectory(dir.resolve("providers"))) {
      throw new IllegalArgumentException("Not a knowledge directory (no providers/): " + dir);
    }
    return load(dir, version(dir));
  }

  /** Load the copy packaged in the knowledge jar on the classpath. */
  public static Knowledge bundled() {
    URL marker = Knowledge.class.getClassLoader().getResource(RESOURCE_ROOT + "/" + VERSION_FILE);
    if (marker == null) {
      throw new IllegalStateException("No bundled knowledge on the classpath (missing " + RESOURCE_ROOT + "/" + VERSION_FILE + ")");
    }
    try {
      URI uri = marker.toURI();
      if ("jar".equals(uri.getScheme())) {
        String spec = uri.toString();
        String jarPart = spec.substring(0, spec.indexOf("!/"));
        FileSystem fs;
        try {
          fs = FileSystems.newFileSystem(URI.create(jarPart), Map.of());
        } catch (java.nio.file.FileSystemAlreadyExistsException e) {
          fs = FileSystems.getFileSystem(URI.create(jarPart));
        }
        Path root = fs.getPath("/" + RESOURCE_ROOT);
        return load(root, version(root));
      }
      if ("resource".equals(uri.getScheme())) {
        // GraalVM native-image resource file system. It is not mounted automatically: calling
        // Path.of on a resource: URI before this throws "The Native Image Resource File System
        // is not present", which made the native binary unable to read its own knowledge base
        // anywhere except a directory that happened to contain a knowledge/ folder.
        FileSystem fs;
        try {
          fs = FileSystems.newFileSystem(URI.create("resource:/"), Map.of());
        } catch (java.nio.file.FileSystemAlreadyExistsException e) {
          fs = FileSystems.getFileSystem(URI.create("resource:/"));
        }
        Path root = fs.getPath("/" + RESOURCE_ROOT);
        return load(root, version(root));
      }
      Path root = Path.of(uri).getParent();
      return load(root, version(root));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot open bundled knowledge: " + e.getMessage(), e);
    }
  }

  /** The trimmed content of VERSION at a knowledge root, or {@link #UNVERSIONED} without one. */
  static String version(Path root) {
    Path file = root.resolve(VERSION_FILE);
    if (!Files.isRegularFile(file)) return UNVERSIONED;
    String v = readString(file).trim();
    return v.isEmpty() ? UNVERSIONED : v;
  }

  private static Knowledge load(Path root, String version) {
    List<Provider> providers = new ArrayList<>();
    List<Change> changes = new ArrayList<>();
    Path providersDir = root.resolve("providers");
    for (Path pdir : listDirs(providersDir)) {
      JsonNode profile = Yaml.read(open(pdir.resolve("provider.yaml")), pdir + "/provider.yaml");
      JsonNode det = Files.exists(pdir.resolve("detectors.yaml"))
          ? Yaml.read(open(pdir.resolve("detectors.yaml")), pdir + "/detectors.yaml")
          : null;
      providers.add(Loaders.provider(profile, det));
      Path changesDir = pdir.resolve("changes");
      if (Files.isDirectory(changesDir)) {
        for (Path cf : listFiles(changesDir, ".yaml")) {
          changes.add(Loaders.change(Yaml.read(open(cf), cf.toString()), relative(root, cf)));
        }
      }
    }
    providers.sort(Comparator.comparing(Provider::id));
    changes.sort(Comparator.comparing(Change::id));

    List<Fixture> fixtures = new ArrayList<>();
    Path fixturesDir = root.resolve("fixtures");
    if (Files.isDirectory(fixturesDir)) {
      for (Path fdir : listDirs(fixturesDir)) {
        Path ef = fdir.resolve("expected-findings.json");
        List<Finding.Pair> pairs = Files.exists(ef)
            ? Json.read(readString(ef), new TypeReference<List<Finding.Pair>>() {})
            : List.of();
        fixtures.add(new Fixture(
            fdir.getFileName().toString(),
            fdir,
            fdir.resolve("repo"),
            fdir.resolve("expected-inventory.json"),
            ef,
            pairs));
      }
    }
    fixtures.sort(Comparator.comparing(Fixture::name));
    return new Knowledge(version, providers, changes, fixtures, root);
  }

  public String version() {
    return version;
  }

  public List<Provider> providers() {
    return providers;
  }

  public List<Change> changes() {
    return changes;
  }

  public List<Fixture> fixtures() {
    return fixtures;
  }

  public Path root() {
    return root;
  }

  public Provider provider(String id) {
    return providers.stream().filter(p -> p.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown provider " + id));
  }

  static String readString(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + p, e);
    }
  }

  private static InputStream open(Path p) {
    try {
      return Files.newInputStream(p);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot open " + p, e);
    }
  }

  private static List<Path> listDirs(Path dir) {
    if (!Files.isDirectory(dir)) return Collections.emptyList();
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Path> listFiles(Path dir, String suffix) {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(suffix))
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String relative(Path root, Path p) {
    return root.relativize(p).toString().replace('\\', '/');
  }
}
