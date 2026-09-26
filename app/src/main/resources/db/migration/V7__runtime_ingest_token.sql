-- Ingest tokens: how a customer's telemetry proves which repository it reports for.
-- docs/13-runtime-observation.md.
--
-- One token belongs to one repository, and a member with write access to that
-- repository creates or revokes it from the dashboard. The token names the
-- repository, so an export needs no docswatcher.repo attribute and cannot write
-- into any other repository whatever its payload says.
--
-- Only the SHA-256 of the token is stored, as with user_session: a copy of this
-- table is not a set of working credentials. The first characters are kept in
-- clear so a person can tell their tokens apart.
create table runtime_ingest_token (
  id           bigserial   primary key,
  repo_id      bigint      not null references repo(id) on delete cascade,
  token_hash   bytea       not null unique,
  prefix       text        not null,
  label        text        not null,
  -- The GitHub login of the member who created it, or 'owner' for the API token.
  created_by   text        not null,
  created_at   timestamptz not null default now(),
  -- Touched at most once a minute, so the ingest path does not write a row per export.
  last_used_at timestamptz,
  -- Revoked tokens are kept, not deleted, so the dashboard can say who had one and when it ended.
  revoked_at   timestamptz
);
create index runtime_ingest_token_repo_idx on runtime_ingest_token (repo_id) where revoked_at is null;
