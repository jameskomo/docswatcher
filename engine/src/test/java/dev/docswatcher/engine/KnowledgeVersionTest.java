package dev.docswatcher.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * knowledge/VERSION is the one knowledge base version. A directory load, the bundled classpath
 * copy and the web build all report its content, so the CLI, the MCP server, the app and the site
 * can never disagree about which knowledge base answered.
 */
class KnowledgeVersionTest {

  static String versionFile() throws IOException {
    return Files.readString(TestSupport.knowledgeDir().resolve(Knowledge.VERSION_FILE)).trim();
  }

  @Test
  void theVersionFileHoldsADateVersion() throws IOException {
    assertThat(versionFile()).matches("\\d{4}\\.\\d{2}\\.\\d{2}");
  }

  @Test
  void aDirectoryLoadReportsTheVersionFile() throws IOException {
    assertThat(TestSupport.knowledge().version()).isEqualTo(versionFile());
  }

  @Test
  void theBundledCopyReportsTheSameVersionFile() throws IOException {
    assertThat(Knowledge.bundled().version()).isEqualTo(versionFile());
  }

  @Test
  void surroundingWhitespaceIsNotPartOfTheVersion(@TempDir Path dir) throws IOException {
    Files.createDirectories(dir.resolve("providers"));
    Files.writeString(dir.resolve(Knowledge.VERSION_FILE), "  2031.01.02\n\n");
    assertThat(Knowledge.load(dir).version()).isEqualTo("2031.01.02");
  }

  @Test
  void onlyAMissingOrEmptyVersionFileFallsBack(@TempDir Path dir) throws IOException {
    Files.createDirectories(dir.resolve("providers"));
    assertThat(Knowledge.load(dir).version()).isEqualTo(Knowledge.UNVERSIONED);
    Files.writeString(dir.resolve(Knowledge.VERSION_FILE), "\n");
    assertThat(Knowledge.load(dir).version()).isEqualTo(Knowledge.UNVERSIONED);
  }
}
