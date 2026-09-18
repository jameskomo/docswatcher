package dev.docwatcher.app.store;

public record StoredContract(
    long repoId,
    String id,
    String provider,
    String kind,
    String key,
    String confidence,
    String evidenceJson,
    String contextJson,
    String firstSeenSha,
    String lastSeenSha) {}
