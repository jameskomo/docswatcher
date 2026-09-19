package dev.docswatcher.app.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextDoc(SdkDoc sdk, String apiVersion) {}
