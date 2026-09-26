package dev.docswatcher.app.gitlab;

import dev.docswatcher.app.store.Repo;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * GitLab connections and the projects they hold (V7__gitlab.sql).
 *
 * <p>A connection is also an {@code installation} row and each project a {@code repo} row, both
 * marked {@code provider = 'gitlab'}, so everything downstream of a repository id is shared with
 * GitHub. Their ids come from {@code gitlab_id_seq}, which only hands out negative numbers.
 */
@Repository
public class GitLabStore {

  public record Connection(
      long id,
      String login,
      String kind,
      long namespaceId,
      String namespacePath,
      byte[] tokenCiphertext,
      long tokenUserId,
      LocalDate tokenExpiresAt,
      String connectedBy,
      OffsetDateTime createdAt) {

    /**
     * Whether a project belongs to this connection's namespace: the project itself, or anything
     * under the group. GitLab paths are case-insensitive.
     */
    public boolean covers(long projectId, String pathWithNamespace) {
      if ("project".equals(kind)) {
        return projectId == namespaceId;
      }
      return pathWithNamespace != null
          && pathWithNamespace.toLowerCase(Locale.ROOT).startsWith(namespacePath.toLowerCase(Locale.ROOT) + "/");
    }
  }

  /** A stored project and the connection that holds it. */
  public record HeldProject(long repoId, long installationId, String login, String fullName, long projectId) {}

  private static final String CONNECTION =
      """
      select c.installation_id as id, i.account_login as login, c.namespace_kind as kind, c.namespace_id, c.namespace_path,
             c.token_ciphertext, c.token_user_id, c.token_expires_at, c.connected_by, c.created_at
      from gitlab_connection c join installation i on i.id = c.installation_id
      """;

  private final JdbcClient jdbc;

  public GitLabStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A fresh id for a connection or a project, never one GitHub could use. */
  public long nextId() {
    return jdbc.sql("select nextval('gitlab_id_seq')").query(Long.class).single();
  }

  @Transactional
  public void create(long id, String login, String kind, long namespaceId, String namespacePath, byte[] ciphertext, long tokenUserId,
      LocalDate tokenExpiresAt, byte[] webhookTokenHash, String connectedBy) {
    jdbc.sql("insert into installation (id, account_login, provider) values (:id, :login, 'gitlab')")
        .param("id", id).param("login", login).update();
    jdbc.sql(
            """
            insert into gitlab_connection (installation_id, namespace_kind, namespace_id, namespace_path, token_ciphertext,
              token_user_id, token_expires_at, webhook_token_hash, connected_by)
            values (:id, :kind, :ns, :path, :cipher, :user, :expires, :hook, :by)
            """)
        .param("id", id).param("kind", kind).param("ns", namespaceId).param("path", namespacePath).param("cipher", ciphertext)
        .param("user", tokenUserId).param("expires", tokenExpiresAt).param("hook", webhookTokenHash).param("by", connectedBy)
        .update();
  }

  /** A maintainer entered a new token for a namespace already connected: the old one is replaced. */
  public void replaceToken(long id, String namespacePath, byte[] ciphertext, long tokenUserId, LocalDate tokenExpiresAt, String connectedBy) {
    jdbc.sql(
            """
            update gitlab_connection set token_ciphertext = :cipher, token_user_id = :user, token_expires_at = :expires,
              namespace_path = :path, connected_by = :by, updated_at = now() where installation_id = :id
            """)
        .param("cipher", ciphertext).param("user", tokenUserId).param("expires", tokenExpiresAt).param("path", namespacePath)
        .param("by", connectedBy).param("id", id).update();
  }

  public Optional<Connection> find(long id) {
    return jdbc.sql(CONNECTION + " where c.installation_id = :id").param("id", id).query(Connection.class).optional();
  }

  public Optional<Connection> findByNamespace(String kind, long namespaceId) {
    return jdbc.sql(CONNECTION + " where c.namespace_kind = :k and c.namespace_id = :n")
        .param("k", kind).param("n", namespaceId).query(Connection.class).optional();
  }

  /** The connection a webhook token belongs to, by the token's SHA-256. */
  public Optional<Connection> findByWebhookTokenHash(byte[] hash) {
    return jdbc.sql(CONNECTION + " where c.webhook_token_hash = :h").param("h", hash).query(Connection.class).optional();
  }

  public byte[] webhookTokenHash(long id) {
    return jdbc.sql("select webhook_token_hash from gitlab_connection where installation_id = :id").param("id", id).query(byte[].class).single();
  }

  public List<Connection> all() {
    return jdbc.sql(CONNECTION + " order by i.account_login, c.namespace_path").query(Connection.class).list();
  }

  /** Removes the connection with everything it holds: its projects, their contracts, findings and runs. */
  public void delete(long id) {
    jdbc.sql("delete from installation where id = :id and provider = 'gitlab'").param("id", id).update();
  }

  public Optional<Repo> findProject(long projectId) {
    return jdbc.sql("select r.* from repo r join gitlab_project g on g.repo_id = r.id where g.project_id = :p")
        .param("p", projectId).query(Repo.class).optional();
  }

  public long projectId(long repoId) {
    return jdbc.sql("select project_id from gitlab_project where repo_id = :r").param("r", repoId).query(Long.class).single();
  }

  /**
   * Adds a project to a connection and returns its repository id, or empty when another connection
   * already holds it. Racing adds of one project leave one row: the unique project id decides.
   */
  @Transactional
  public Optional<Long> addProject(long installationId, long projectId, String fullName, String defaultBranch) {
    if (findProject(projectId).isPresent()) {
      return Optional.empty();
    }
    long repoId = nextId();
    jdbc.sql("insert into repo (id, installation_id, full_name, default_branch, provider) values (:id, :i, :n, :b, 'gitlab')")
        .param("id", repoId).param("i", installationId).param("n", fullName).param("b", defaultBranch).update();
    int claimed = jdbc.sql("insert into gitlab_project (repo_id, installation_id, project_id) values (:r, :i, :p) on conflict (project_id) do nothing")
        .param("r", repoId).param("i", installationId).param("p", projectId).update();
    if (claimed == 0) {
      // Another add won the race for this project between the check and the insert.
      jdbc.sql("delete from repo where id = :id").param("id", repoId).update();
      return Optional.empty();
    }
    return Optional.of(repoId);
  }

  /** A project renamed, moved within the namespace, or given another default branch. */
  public void updateProject(long repoId, String fullName, String defaultBranch) {
    jdbc.sql("update repo set full_name = :n, default_branch = :b where id = :id and provider = 'gitlab'")
        .param("n", fullName).param("b", defaultBranch).param("id", repoId).update();
  }

  public int countProjects(long installationId) {
    return jdbc.sql("select count(*) from gitlab_project where installation_id = :i").param("i", installationId).query(Integer.class).single();
  }

  public List<HeldProject> heldProjects() {
    return jdbc.sql(
            """
            select g.repo_id, g.installation_id, i.account_login as login, r.full_name, g.project_id
            from gitlab_project g join repo r on r.id = g.repo_id join installation i on i.id = g.installation_id
            order by i.account_login, r.full_name
            """)
        .query(HeldProject.class).list();
  }
}
