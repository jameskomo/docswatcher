package dev.docswatcher.app.store;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FindingStore {

  private static final String COLUMNS =
      "repo_id, contract_id, change_id, id, severity, effective, status, snoozed_until, issue_number, fix_pr_url, opened_at, closed_at";

  private final JdbcClient jdbc;

  public FindingStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insertOpen(long repoId, String contractId, String changeId, String id, String severity, LocalDate effective) {
    jdbc.sql(
            """
            insert into finding (repo_id, contract_id, change_id, id, severity, effective, status)
            values (:repo, :contract, :change, :id, :severity, :effective, 'open')
            on conflict (repo_id, contract_id, change_id) do update set
              severity = excluded.severity, effective = excluded.effective, closed_at = null,
              status = case when finding.status = 'fixed' then 'open' else finding.status end
            """)
        .param("repo", repoId)
        .param("contract", contractId)
        .param("change", changeId)
        .param("id", id)
        .param("severity", severity)
        .param("effective", effective)
        .update();
  }

  public void close(long repoId, String contractId, String changeId) {
    jdbc.sql("update finding set status = 'fixed', closed_at = now() where repo_id = :r and contract_id = :c and change_id = :ch")
        .param("r", repoId).param("c", contractId).param("ch", changeId).update();
  }

  public void setStatus(long repoId, String contractId, String changeId, String status, LocalDate snoozedUntil) {
    jdbc.sql("update finding set status = :s, snoozed_until = :u where repo_id = :r and contract_id = :c and change_id = :ch")
        .param("s", status).param("u", snoozedUntil).param("r", repoId).param("c", contractId).param("ch", changeId).update();
  }

  public void setIssueNumber(long repoId, String contractId, String changeId, int issueNumber) {
    jdbc.sql("update finding set issue_number = :n where repo_id = :r and contract_id = :c and change_id = :ch")
        .param("n", issueNumber).param("r", repoId).param("c", contractId).param("ch", changeId).update();
  }

  public void setFixPrUrl(long repoId, String contractId, String changeId, String url) {
    jdbc.sql("update finding set fix_pr_url = :u where repo_id = :r and contract_id = :c and change_id = :ch")
        .param("u", url).param("r", repoId).param("c", contractId).param("ch", changeId).update();
  }

  public Optional<StoredFinding> find(long repoId, String contractId, String changeId) {
    return jdbc.sql("select " + COLUMNS + " from finding where repo_id = :r and contract_id = :c and change_id = :ch")
        .param("r", repoId).param("c", contractId).param("ch", changeId).query(StoredFinding.class).optional();
  }

  public Optional<StoredFinding> findByIssue(long repoId, int issueNumber) {
    return jdbc.sql("select " + COLUMNS + " from finding where repo_id = :r and issue_number = :n")
        .param("r", repoId).param("n", issueNumber).query(StoredFinding.class).optional();
  }

  public List<StoredFinding> forRepo(long repoId) {
    return jdbc.sql("select " + COLUMNS + " from finding where repo_id = :r order by effective nulls last, severity, contract_id")
        .param("r", repoId).query(StoredFinding.class).list();
  }

  public List<StoredFinding> openForRepo(long repoId) {
    return jdbc.sql("select " + COLUMNS + " from finding where repo_id = :r and status <> 'fixed' order by contract_id, change_id")
        .param("r", repoId).query(StoredFinding.class).list();
  }

  public List<StoredFinding> forLogin(String login) {
    return jdbc.sql(
            """
            select f.repo_id, f.contract_id, f.change_id, f.id, f.severity, f.effective, f.status, f.snoozed_until,
                   f.issue_number, f.fix_pr_url, f.opened_at, f.closed_at
            from finding f join repo r on r.id = f.repo_id join installation i on i.id = r.installation_id
            where i.account_login = :login order by f.effective nulls last, f.severity, f.repo_id, f.contract_id
            """)
        .param("login", login).query(StoredFinding.class).list();
  }

  public List<StoredFinding> forLoginAndChange(String login, String changeId) {
    return jdbc.sql(
            """
            select f.repo_id, f.contract_id, f.change_id, f.id, f.severity, f.effective, f.status, f.snoozed_until,
                   f.issue_number, f.fix_pr_url, f.opened_at, f.closed_at
            from finding f join repo r on r.id = f.repo_id join installation i on i.id = r.installation_id
            where i.account_login = :login and f.change_id = :change order by f.repo_id, f.contract_id
            """)
        .param("login", login).param("change", changeId).query(StoredFinding.class).list();
  }
}
