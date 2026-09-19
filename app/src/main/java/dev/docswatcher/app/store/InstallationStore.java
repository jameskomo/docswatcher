package dev.docswatcher.app.store;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InstallationStore {

  private final JdbcClient jdbc;

  public InstallationStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void upsert(long id, String accountLogin) {
    jdbc.sql(
            """
            insert into installation (id, account_login) values (:id, :login)
            on conflict (id) do update set account_login = excluded.account_login, suspended_at = null
            """)
        .param("id", id)
        .param("login", accountLogin)
        .update();
  }

  public void delete(long id) {
    jdbc.sql("delete from installation where id = :id").param("id", id).update();
  }

  public void suspend(long id, boolean suspended) {
    jdbc.sql("update installation set suspended_at = " + (suspended ? "now()" : "null") + " where id = :id")
        .param("id", id)
        .update();
  }

  public void setKnowledgeVersion(long id, String version) {
    jdbc.sql("update installation set knowledge_version = :v where id = :id").param("v", version).param("id", id).update();
  }

  public Optional<Installation> find(long id) {
    return jdbc.sql("select * from installation where id = :id").param("id", id).query(Installation.class).optional();
  }

  public List<Installation> findByLogin(String login) {
    return jdbc.sql("select * from installation where account_login = :login").param("login", login).query(Installation.class).list();
  }

  public List<Installation> all() {
    return jdbc.sql("select * from installation").query(Installation.class).list();
  }
}
