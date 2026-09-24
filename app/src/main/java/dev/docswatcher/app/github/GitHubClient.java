package dev.docswatcher.app.github;

import java.util.List;
import java.util.Map;

/** Everything the app asks GitHub for. One real implementation, one fake for tests. */
public interface GitHubClient {

  record InstallationRepo(long id, String fullName, String defaultBranch) {}

  record CloneSource(String uri, String username, String password) {}

  List<InstallationRepo> listInstallationRepos(long installationId);

  CloneSource cloneSource(long installationId, String fullName);

  void createCheckRun(long installationId, String fullName, String headSha, String name, String conclusion, String title, String summary);

  int createIssue(long installationId, String fullName, String title, String body, List<String> labels);

  void closeIssue(long installationId, String fullName, int issueNumber, String comment);

  void addLabels(long installationId, String fullName, int issueNumber, List<String> labels);

  void repositoryDispatch(long installationId, String fullName, String eventType, Map<String, Object> clientPayload);

  /**
   * The actor's permission on the repository: "admin", "maintain", "write", "triage", "read" or
   * "none". Needed because applying a label is a triage capability, while the actions a label
   * triggers here spend the installation's write authority.
   */
  String collaboratorPermission(long installationId, String fullName, String login);

  /**
   * Drops anything cached for the installation, such as its access token, so the next call
   * authenticates afresh. Called when the installation, its permissions or its repositories change.
   */
  default void forgetInstallation(long installationId) {}
}
