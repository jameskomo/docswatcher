-- Signed-in sessions for the organisation dashboard. docs/adr/0008-sign-in-with-github.md.
--
-- The cookie holds a random value; only its SHA-256 is stored here, so this table
-- is not a list of usable sessions. No GitHub token is stored anywhere: access is
-- the snapshot GitHub gave at sign-in, and it expires with the row.
create table user_session (
  token_hash bytea       primary key,
  github_id  bigint      not null,
  login      text        not null,
  name       text,
  avatar_url text,
  -- [{installationId, login, repos: [{id, fullName, permission}]}]
  access     jsonb       not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index user_session_expires_idx on user_session (expires_at);
