package dev.docswatcher.app.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * The workflow customers install runs a coding agent over code DocsWatcher did not write. Its
 * limits must be configuration the runner enforces, not prose; these tests keep them there.
 */
class FixWorkflowTemplateTest {

  private static final String TEXT = read();
  @SuppressWarnings("unchecked")
  private static final Map<String, Object> WORKFLOW = new Yaml().load(TEXT);

  private static String read() {
    try {
      return new ClassPathResource("templates/docswatcher-fix.yml").getContentAsString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> job(String name) {
    return (Map<String, Object>) ((Map<String, Object>) WORKFLOW.get("jobs")).get(name);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> steps(String job) {
    return (List<Map<String, Object>>) job(job).get("steps");
  }

  private static String runs(String job) {
    return String.join("\n", steps(job).stream().map(s -> String.valueOf(s.getOrDefault("run", ""))).toList());
  }

  @Test
  void theAgentsJobCanReadButNotPush() {
    assertThat(WORKFLOW.get("permissions")).isEqualTo(Map.of());
    assertThat(job("draft").get("permissions")).isEqualTo(Map.of("contents", "read"));
  }

  @Test
  void theAgentCannotRunCommandsReachTheWebOrReadTheEnvironment() {
    String agent = runs("draft");
    assertThat(agent).contains("--disallowedTools \"Bash\" \"WebFetch\" \"WebSearch\" \"Read(//proc/**)\"");
    // Exactly these, each path-scoped: reads of the checkout and the finding, edits of the named files.
    String allowed = agent.lines().filter(l -> l.strip().startsWith("--allowedTools")).findFirst().orElseThrow().strip();
    assertThat(allowed).isEqualTo(
        "--allowedTools \"Read(./**)\" \"Read(/$DOCSWATCHER_DIR/finding.json)\" \"${edit[@]}\" \\");
    assertThat(agent).contains("edit+=(\"Edit(./$p)\")");
  }

  @Test
  void theKeyNeverReachesTheJobThatPushes() {
    assertThat(TEXT.indexOf("ANTHROPIC_API_KEY: ${{")).isLessThan(TEXT.indexOf("open-pr:"));
    assertThat(runs("open-pr")).doesNotContain("ANTHROPIC").doesNotContain("claude");
    assertThat(job("open-pr").get("permissions")).isEqualTo(Map.of("contents", "write", "pull-requests", "write"));
  }

  @Test
  void bothJobsCheckTheChangeAgainstTheNamedFiles() {
    assertThat(runs("draft")).contains("grep -vxF -f \"$DOCSWATCHER_DIR/allowed\"").contains("git diff --cached --summary");
    assertThat(runs("open-pr")).contains("grep -vxF -f \"$dir/allowed\"").contains("'^\\.github/'");
  }

  @Test
  void noPayloadTextIsInterpolatedIntoAScript() {
    assertThat(runs("draft") + runs("open-pr")).doesNotContain("${{");
  }

  @Test
  void everyActionIsPinnedToACommitAndCheckoutsKeepNoToken() {
    Pattern sha = Pattern.compile("^[\\w.-]+/[\\w.-]+@[0-9a-f]{40}$");
    for (String name : List.of("draft", "open-pr")) {
      for (Map<String, Object> step : steps(name)) {
        Object uses = step.get("uses");
        if (uses == null) continue;
        assertThat(uses.toString()).matches(sha);
        if (uses.toString().startsWith("actions/checkout@")) {
          assertThat(step.get("with")).isEqualTo(Map.of("persist-credentials", false));
        }
      }
      assertThat(job(name).get("timeout-minutes")).isNotNull();
    }
  }
}
