package dev.docwatcher.app.store;

public record Repo(long id, long installationId, String fullName, String defaultBranch, String lastScannedSha, boolean production) {

  public String owner() {
    return fullName.substring(0, fullName.indexOf('/'));
  }

  public String name() {
    return fullName.substring(fullName.indexOf('/') + 1);
  }
}
