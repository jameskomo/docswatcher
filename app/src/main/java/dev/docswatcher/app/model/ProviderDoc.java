package dev.docswatcher.app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The slice of a knowledge base provider profile the app needs, which is enough to
 * recognise a provider from a hostname seen in telemetry. The detector tables are
 * deliberately absent: matching rules belong to the engine, not here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderDoc(String id, String name, List<String> baseUrls) {}
