package dev.docswatcher.app.store;

import java.time.OffsetDateTime;

public record ScanRun(
    long id,
    long repoId,
    String sha,
    String trigger,
    String status,
    String engineVersion,
    String knowledgeVersion,
    String statsJson,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    String error,
    OffsetDateTime createdAt) {

  public static final String QUEUED = "queued";
  public static final String RUNNING = "running";
  public static final String DONE = "done";
  public static final String FAILED = "failed";

  public static final String TRIGGER_INSTALL = "install";
  public static final String TRIGGER_PUSH = "push";
  public static final String TRIGGER_REMATCH = "rematch";
  public static final String TRIGGER_MANUAL = "manual";
  /** A push to the organisation's .docswatcher repository, whose API records every repository shares. */
  public static final String TRIGGER_ORG_RECORDS = "org-records";
}
