package dev.docswatcher.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Optional context attached to a contract. Absent fields are omitted from JSON. */
@JsonPropertyOrder({"sdk", "apiVersion"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Context(Sdk sdk, String apiVersion) {

  @JsonPropertyOrder({"ecosystem", "package", "version"})
  public record Sdk(String ecosystem, @com.fasterxml.jackson.annotation.JsonProperty("package") String pkg, String version) {}

  @com.fasterxml.jackson.annotation.JsonIgnore
  public boolean isEmpty() {
    return sdk == null && apiVersion == null;
  }
}
