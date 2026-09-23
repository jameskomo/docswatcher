package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** docs/18-excluding-paths.md. The TypeScript engine runs the same cases in tests/unit/ignore.test.ts. */
class IgnoreTest {

  private static final Path CASES = Path.of("src/test/resources/ignore-cases.json");

  @TestFactory
  Stream<DynamicTest> sharedCases() throws IOException {
    JsonNode root = Json.tree(Files.readString(CASES));
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode c : root.get("cases")) {
      tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
        Map<String, String> files = new LinkedHashMap<>();
        c.get("ignoreFiles").fields().forEachRemaining(e -> files.put(e.getKey(), e.getValue().asText()));
        List<String> exclude = new ArrayList<>();
        if (c.has("exclude")) c.get("exclude").forEach(x -> exclude.add(x.asText()));
        Ignore ignore = Ignore.of(files, exclude);
        c.get("expect").fields().forEachRemaining(e ->
            assertThat(ignore.ignored(e.getKey())).as(e.getKey()).isEqualTo(e.getValue().asBoolean()));
      }));
    }
    return tests.stream();
  }

  @Test
  void theWalkNeverReadsExcludedFiles(@TempDir Path repo) throws IOException {
    write(repo, ".gitignore", "generated/\n");
    write(repo, "web/.gitignore", ".output/\n");
    write(repo, ".docswatcherignore", "samples/\n");
    write(repo, "app.py", "x");
    write(repo, "generated/models.json", "x");
    write(repo, "web/.output/bundle.js", "x");
    write(repo, "samples/old.py", "x");

    FileTree tree = FileTree.read(repo, List.of("app.py"));
    assertThat(tree.files).extracting(f -> f.path)
        .containsExactly(".docswatcherignore", ".gitignore", "web/.gitignore");
    assertThat(tree.skipped).isEqualTo(4);

    assertThat(FileTree.read(repo, List.of()).files).extracting(f -> f.path).contains("app.py");
  }

  private static void write(Path root, String rel, String text) throws IOException {
    Path p = root.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, text);
  }
}
