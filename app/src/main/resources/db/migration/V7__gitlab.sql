-- GitLab, beside the GitHub App. docs/adr/0012-gitlab.md.
--
-- A GitLab group or project connected to DocsWatcher is an installation, and each of its
-- projects is a repo, so contracts, findings, scan runs and the dashboard work unchanged. Their
-- ids come from gitlab_id_seq, which counts down from -1: GitHub's ids are positive, so the two
-- can never collide in the tables keyed on them.
alter table installation add column provider text not null default 'github';
alter table repo add column provider text not null default 'github';

create sequence gitlab_id_seq increment by -1 minvalue -9223372036854775808 maxvalue -1 start with -1;

-- One connected namespace. The access token is the maintainer's group or project access token,
-- AES-256-GCM encrypted under docswatcher.gitlab.token-key (never stored in plain text). The
-- webhook token only has to be checked, so only its SHA-256 is kept.
create table gitlab_connection (
  installation_id    bigint      primary key references installation(id) on delete cascade,
  namespace_kind     text        not null check (namespace_kind in ('group', 'project')),
  namespace_id       bigint      not null,
  namespace_path     text        not null,
  token_ciphertext   bytea       not null,
  token_user_id      bigint      not null,
  token_expires_at   date,
  webhook_token_hash bytea       not null unique,
  connected_by       text        not null,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  unique (namespace_kind, namespace_id)
);

-- A GitLab project DocsWatcher scans. A project reachable through two connections (a group and
-- one of its subgroups) is held once, by the connection that found it first.
create table gitlab_project (
  repo_id         bigint primary key references repo(id) on delete cascade,
  installation_id bigint not null references installation(id) on delete cascade,
  project_id      bigint not null unique
);
create index gitlab_project_installation_idx on gitlab_project (installation_id);

-- Which provider a session signed in with. github_id holds that provider's user id; the column
-- predates GitLab and keeps its name so nothing that reads it has to change.
alter table user_session add column provider text not null default 'github';
