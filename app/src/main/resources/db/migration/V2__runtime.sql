-- Runtime observations: what the code actually calls, and what the provider
-- said about it in the response.
--
-- One row per repository, day, and endpoint. Reports arrive continuously from
-- customer telemetry, so the write path must be a cheap upsert that increments
-- a counter rather than an insert per HTTP call.
create table runtime_observation (
  repo_id            bigint      not null references repo(id) on delete cascade,
  observed_date      date        not null,
  host               text        not null,
  method             text        not null,
  path               text        not null,

  -- Null when the host matched no provider in the knowledge base. Kept rather
  -- than discarded: an unattributed call carrying a Sunset header is still a
  -- fact worth showing, and it tells us which provider to add next.
  provider           text,
  -- Null when no scanned contract matched. This is the join to a finding.
  contract_id        text,

  -- The only two headers we store. Nothing else from the response is recorded.
  deprecation_header text,
  sunset_header      text,

  call_count         bigint      not null default 0,
  first_seen         timestamptz not null default now(),
  last_seen          timestamptz not null default now(),

  primary key (repo_id, observed_date, host, method, path)
);

-- Joining a finding to its runtime evidence is the whole point, so this index
-- carries the lookup the findings API makes for every finding it returns.
create index runtime_observation_contract_idx
  on runtime_observation (repo_id, contract_id)
  where contract_id is not null;

-- Listing a repository's observations, newest day first.
create index runtime_observation_repo_date_idx
  on runtime_observation (repo_id, observed_date desc);
