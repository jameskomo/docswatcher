package dev.docswatcher.app.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContractDoc(
    String id,
    String provider,
    String kind,
    String key,
    String confidence,
    List<EvidenceDoc> evidence,
    ContextDoc context) {}
