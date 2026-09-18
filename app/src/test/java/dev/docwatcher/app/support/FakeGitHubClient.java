package dev.docwatcher.app.support;

import dev.docwatcher.app.github.GitHubClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeGitHubClient implements GitHubClient {

  public record Call(String method, Object... args) {}

  public final List<Call> calls = new ArrayList<>();
  public List<InstallationRepo> installationRepos = List.of();
  public CloneSource cloneSource = new CloneSource("file:///nowhere", null, null);
  private final AtomicInteger issueCounter = new AtomicInteger(100);

  public void reset() {
    calls.clear();
    installationRepos = List.of();
    issueCounter.set(100);
  }

  public List<Call> calls(String method) {
    return calls.stream().filter(c -> c.method().equals(method)).toList();
  }

  @Override
  public List<InstallationRepo> listInstallationRepos(long installationId) {
    calls.add(new Call("listInstallationRepos", installationId));
    return installationRepos;
  }

  @Override
  public CloneSource cloneSource(long installationId, String fullName) {
    calls.add(new Call("cloneSource", installationId, fullName));
    return cloneSource;
  }

  @Override
  public void createCheckRun(long installationId, String fullName, String headSha, String name, String conclusion, String title, String summary) {
    calls.add(new Call("createCheckRun", installationId, fullName, headSha, name, conclusion, title, summary));
  }

  @Override
  public int createIssue(long installationId, String fullName, String title, String body, List<String> labels) {
    int n = issueCounter.incrementAndGet();
    calls.add(new Call("createIssue", installationId, fullName, title, body, labels, n));
    return n;
  }

  @Override
  public void closeIssue(long installationId, String fullName, int issueNumber, String comment) {
    calls.add(new Call("closeIssue", installationId, fullName, issueNumber, comment));
  }

  @Override
  public void addLabels(long installationId, String fullName, int issueNumber, List<String> labels) {
    calls.add(new Call("addLabels", installationId, fullName, issueNumber, labels));
  }

  @Override
  public void repositoryDispatch(long installationId, String fullName, String eventType, Map<String, Object> clientPayload) {
    calls.add(new Call("repositoryDispatch", installationId, fullName, eventType, clientPayload));
  }
}
