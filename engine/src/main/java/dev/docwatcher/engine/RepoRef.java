package dev.docwatcher.engine;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Identity of the repository a scan ran against. Any field may be null for a local scan. */
@JsonPropertyOrder({"host", "owner", "name", "ref", "sha"})
public record RepoRef(String host, String owner, String name, String ref, String sha) {

  public static RepoRef local(String name) {
    return new RepoRef("local", null, name, null, null);
  }

  /** owner/name when both are known, else name, else "local". Used in finding ids. */
  public String fullName() {
    if (owner != null && name != null) return owner + "/" + name;
    if (name != null) return name;
    return "local";
  }
}
