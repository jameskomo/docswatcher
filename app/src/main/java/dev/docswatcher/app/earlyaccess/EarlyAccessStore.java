package dev.docswatcher.app.earlyaccess;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Early-access requests from the Teams page. docs/adr/0005-early-access-requests.md. */
@Repository
public class EarlyAccessStore {

  /** One request as stored. */
  public record Request(
      String email, String company, String repositories, String providers, String interest, String message,
      int requests, OffsetDateTime firstAt, OffsetDateTime lastAt) {}

  private final JdbcClient jdbc;

  public EarlyAccessStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Stores a request, or updates the earlier one from the same address. */
  public void save(String email, String company, String repositories, String providers, String interest, String message) {
    jdbc.sql(
            """
            insert into early_access (email, company, repositories, providers, interest, message)
            values (:email, :company, :repositories, :providers, :interest, :message)
            on conflict (email) do update set
              company = excluded.company, repositories = excluded.repositories,
              providers = excluded.providers, interest = excluded.interest, message = excluded.message,
              requests = early_access.requests + 1, last_at = now()
            """)
        .param("email", email)
        .param("company", company)
        .param("repositories", repositories)
        .param("providers", providers)
        .param("interest", interest)
        .param("message", message)
        .update();
  }

  /** Newest first. */
  public List<Request> all() {
    return jdbc.sql(
            "select email, company, repositories, providers, interest, message, requests, first_at, last_at "
                + "from early_access order by last_at desc")
        .query((rs, i) -> new Request(
            rs.getString("email"), rs.getString("company"), rs.getString("repositories"), rs.getString("providers"),
            rs.getString("interest"), rs.getString("message"), rs.getInt("requests"),
            rs.getObject("first_at", OffsetDateTime.class), rs.getObject("last_at", OffsetDateTime.class)))
        .list();
  }
}
