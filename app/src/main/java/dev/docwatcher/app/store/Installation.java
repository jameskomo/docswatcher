package dev.docwatcher.app.store;

import java.time.OffsetDateTime;

public record Installation(long id, String accountLogin, String knowledgeVersion, OffsetDateTime createdAt, OffsetDateTime suspendedAt) {}
