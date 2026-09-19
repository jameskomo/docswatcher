package dev.docswatcher.app.runtime;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RuntimeObservationStore {

  private static final String COLUMNS =
      "repo_id, observed_date, host, method, path, provider, contract_id, "
          + "deprecation_header, sunset_header, call_count, first_seen, last_seen";

  private final JdbcClient jdbc;

  public RuntimeObservationStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Adds calls to one endpoint on one day.
   *
   * <p>Headers use coalesce rather than assignment so a later batch of header-less spans
   * cannot erase a notice the provider already sent. The row only ever gains information.
   */
  public void record(long repoId, LocalDate date, HttpCall call, String provider, String contractId, long calls) {
    jdbc.sql(
            """
            insert into runtime_observation
              (repo_id, observed_date, host, method, path, provider, contract_id,
               deprecation_header, sunset_header, call_count)
            values (:repo, :date, :host, :method, :path, :provider, :contract, :dep, :sunset, :calls)
            on conflict (repo_id, observed_date, host, method, path) do update set
              call_count         = runtime_observation.call_count + excluded.call_count,
              provider           = coalesce(excluded.provider, runtime_observation.provider),
              contract_id        = coalesce(excluded.contract_id, runtime_observation.contract_id),
              deprecation_header = coalesce(excluded.deprecation_header, runtime_observation.deprecation_header),
              sunset_header      = coalesce(excluded.sunset_header, runtime_observation.sunset_header),
              last_seen          = now()
            """)
        .param("repo", repoId)
        .param("date", date)
        .param("host", call.host())
        .param("method", call.method())
        .param("path", call.path())
        .param("provider", provider)
        .param("contract", contractId)
        .param("dep", call.deprecation())
        .param("sunset", call.sunset())
        .param("calls", calls)
        .update();
  }

  public List<RuntimeObservation> forRepo(long repoId) {
    return jdbc.sql("select " + COLUMNS + " from runtime_observation where repo_id = :r order by observed_date desc, call_count desc")
        .param("r", repoId).query(RuntimeObservation.class).list();
  }

  public List<RuntimeObservation> forContract(long repoId, String contractId) {
    return jdbc.sql("select " + COLUMNS + " from runtime_observation where repo_id = :r and contract_id = :c order by observed_date desc")
        .param("r", repoId).param("c", contractId).query(RuntimeObservation.class).list();
  }

  /** Every observation for a repository that is attributed to a contract, for the findings API. */
  public List<RuntimeObservation> attributedForRepo(long repoId) {
    return jdbc.sql("select " + COLUMNS + " from runtime_observation where repo_id = :r and contract_id is not null order by observed_date desc")
        .param("r", repoId).query(RuntimeObservation.class).list();
  }
}
