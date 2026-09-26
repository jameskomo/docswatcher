package dev.docswatcher.app.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitLabForgeTest {

  @Test
  void only_a_failing_check_fails_the_commit_status() {
    assertThat(GitLabForge.state("failure")).isEqualTo("failed");
    assertThat(GitLabForge.state("neutral")).isEqualTo("success");
    assertThat(GitLabForge.state("success")).isEqualTo("success");
  }

  @Test
  void a_description_is_cut_to_what_gitlab_accepts() {
    assertThat(GitLabForge.truncate("short")).isEqualTo("short");
    String cut = GitLabForge.truncate("x".repeat(400));
    assertThat(cut).hasSize(GitLabForge.MAX_DESCRIPTION).endsWith("…");
  }

  @Test
  void a_connection_covers_its_group_s_projects_and_nothing_that_only_shares_a_prefix() {
    GitLabStore.Connection group = new GitLabStore.Connection(-1, "acme", "group", 55, "acme/platform", new byte[0], 1, null, "owner", null);
    assertThat(group.covers(1, "acme/platform/api")).isTrue();
    assertThat(group.covers(1, "ACME/Platform/deep/api")).isTrue();
    assertThat(group.covers(1, "acme/platformer/api")).isFalse();
    assertThat(group.covers(1, "acme/platform")).isFalse();
    assertThat(group.covers(1, null)).isFalse();
    GitLabStore.Connection project = new GitLabStore.Connection(-2, "acme", "project", 901, "acme/api", new byte[0], 1, null, "owner", null);
    assertThat(project.covers(901, "anything")).isTrue();
    assertThat(project.covers(902, "acme/api")).isFalse();
  }

  @Test
  void a_connection_is_grouped_under_its_top_level_group() {
    assertThat(GitLabConnectionService.topLevel("acme/platform/api")).isEqualTo("acme");
    assertThat(GitLabConnectionService.topLevel("acme")).isEqualTo("acme");
  }
}
