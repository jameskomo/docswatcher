package dev.docswatcher.app.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnconfiguredGitHubClientTest {

  private final GitHubClient subject = new GitHubFallbackConfig().unconfiguredGitHubClient();

  @Test
  void the_bean_exists_so_the_application_can_start_without_github() {
    assertThat(subject).isNotNull();
  }

  @Test
  void every_call_fails_with_an_actionable_message() {
    assertThatThrownBy(() -> subject.listInstallationRepos(1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GITHUB_APP_ID");
    assertThatThrownBy(() -> subject.cloneSource(1, "a/b")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> subject.createIssue(1, "a/b", "t", "b", List.of())).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> subject.closeIssue(1, "a/b", 1, "c")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> subject.addLabels(1, "a/b", 1, List.of())).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> subject.createCheckRun(1, "a/b", "sha", "n", "c", "t", "s")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> subject.repositoryDispatch(1, "a/b", "e", Map.of())).isInstanceOf(IllegalStateException.class);
  }
}
