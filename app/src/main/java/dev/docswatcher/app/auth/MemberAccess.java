package dev.docswatcher.app.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opens an API handler to signed-in members, not only to the token holder.
 *
 * <p>Opt in, so the default is closed: a handler without this annotation answers a member with
 * 403 however its path looks. With it, {@link AccessInterceptor} checks the path's scope before
 * the handler runs. A {@code {login}} variable must name an organisation the member can see; a
 * {@code {repoId}} variable must name a repository they can read, or write when {@link #value()}
 * says so. Anything the member may not see answers 404, so its existence is not disclosed.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MemberAccess {

  Level value() default Level.READ;

  enum Level {
    /** Reading what the member can see. Organisation queries are filtered to their repositories. */
    READ,
    /** Acting on a repository's findings: write, maintain or admin on it, the bar the label path sets. */
    WRITE
  }
}
