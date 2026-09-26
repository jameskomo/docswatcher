package dev.docswatcher.app.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opens a handler to a {@link Viewer.Ingest} caller, which reaches nothing else.
 *
 * <p>Opt in, like {@link MemberAccess}: an ingest token presented anywhere without this marker is
 * refused, even if the filter's route check were ever to widen by mistake.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IngestAccess {}
