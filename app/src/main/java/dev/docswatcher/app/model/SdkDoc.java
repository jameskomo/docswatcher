package dev.docswatcher.app.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SdkDoc(String ecosystem, @JsonProperty("package") String pkg, String version) {}
