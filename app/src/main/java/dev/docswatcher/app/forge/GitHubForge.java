package dev.docswatcher.app.forge;

import dev.docswatcher.app.config.AppProperties;
import dev.docswatcher.app.github.GitHubClient;
import dev.docswatcher.app.scan.ScanRunner;
import dev.docswatcher.app.store.Repo;
import java.util.List;
import org.springframework.stereotype.Component;

/** The GitHub App, as a {@link Forge}: every call goes to {@link GitHubClient} exactly as before. */
@Component
public class GitHubForge implements Forge {

  private final GitHubClient client;
  private final AppProperties.GitHub github;

  public GitHubForge(GitHubClient client, AppProperties properties) {
    this.client = client;
    this.github = properties.github();
  }

  @Override
  public String host() {
    return Repo.GITHUB;
  }

  @Override
  public GitHubClient.CloneSource cloneSource(Repo repo) {
    return client.cloneSource(repo.installationId(), repo.fullName());
  }

  @Override
  public void reportScan(Repo repo, String sha, String conclusion, String title, String summary) {
    client.createCheckRun(repo.installationId(), repo.fullName(), sha, ScanRunner.CHECK_NAME, conclusion, title, summary);
  }

  @Override
  public int openIssue(Repo repo, String title, String body, List<String> labels) {
    return client.createIssue(repo.installationId(), repo.fullName(), title, body, labels);
  }

  @Override
  public void closeIssue(Repo repo, int number, String comment) {
    client.closeIssue(repo.installationId(), repo.fullName(), number, comment);
  }

  @Override
  public String blobBase(Repo repo, String sha) {
    return "https://github.com/" + repo.fullName() + "/blob/" + sha + "/";
  }

  @Override
  public String fixLabel() {
    return github.fixLabel();
  }
}
