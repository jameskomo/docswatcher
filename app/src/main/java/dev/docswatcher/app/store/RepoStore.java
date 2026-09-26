package dev.docswatcher.app.store;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepoStore {

  private final JdbcClient jdbc;

  public RepoStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void upsert(long id, long installationId, String fullName, String defaultBranch) {
    jdbc.sql(
            """
            insert into repo (id, installation_id, full_name, default_branch) values (:id, :inst, :name, :branch)
            on conflict (id) do update set installation_id = excluded.installation_id,
              full_name = excluded.full_name, default_branch = excluded.default_branch
            """)
        .param("id", id)
        .param("inst", installationId)
        .param("name", fullName)
        .param("branch", defaultBranch)
        .update();
  }

  public void delete(long id) {
    jdbc.sql("delete from repo where id = :id").param("id", id).update();
  }

  public Optional<Repo> find(long id) {
    return jdbc.sql("select * from repo where id = :id").param("id", id).query(Repo.class).optional();
  }

  /**
   * Resolves a repository by name without an owner. Safe only where the name did not come from a
   * caller: full_name identifies a GitHub repository, not an owner of the data, and a deployment
   * can hold many installations the moment the App is installed more than once. Caller-supplied
   * names must use {@link #findByFullName(long, String)}.
   *
   * <p>GitHub repositories only: a GitLab project can carry the same path as a GitHub repository,
   * and a name that matched both would be no answer at all.
   */
  public Optional<Repo> findByFullName(String fullName) {
    return jdbc.sql("select * from repo where full_name = :n and provider = 'github'").param("n", fullName).query(Repo.class).optional();
  }

  /** Resolves a repository by name inside one installation. */
  public Optional<Repo> findByFullName(long installationId, String fullName) {
    return jdbc.sql("select * from repo where installation_id = :i and full_name = :n")
        .param("i", installationId)
        .param("n", fullName)
        .query(Repo.class)
        .optional();
  }

  public List<Repo> forLogin(String login) {
    return jdbc.sql(
            """
            select r.* from repo r join installation i on i.id = r.installation_id
            where i.account_login = :login order by r.full_name
            """)
        .param("login", login)
        .query(Repo.class)
        .list();
  }

  public List<Repo> forInstallation(long installationId) {
    return jdbc.sql("select * from repo where installation_id = :id order by full_name").param("id", installationId).query(Repo.class).list();
  }

  public void setLastScannedSha(long id, String sha) {
    jdbc.sql("update repo set last_scanned_sha = :sha where id = :id").param("sha", sha).param("id", id).update();
  }

  public void setProduction(long id, boolean production) {
    jdbc.sql("update repo set production = :p where id = :id").param("p", production).param("id", id).update();
  }
}
