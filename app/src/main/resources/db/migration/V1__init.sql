create table installation (
  id                bigint primary key,
  account_login     text not null,
  knowledge_version text,
  created_at        timestamptz not null default now(),
  suspended_at      timestamptz
);

create table repo (
  id               bigint primary key,
  installation_id  bigint not null references installation(id) on delete cascade,
  full_name        text not null,
  default_branch   text not null,
  last_scanned_sha text,
  production       boolean not null default true
);
create index repo_installation_idx on repo(installation_id);

create table contract (
  repo_id        bigint not null references repo(id) on delete cascade,
  id             text not null,
  provider       text not null,
  kind           text not null,
  key            text not null,
  confidence     text not null,
  evidence       jsonb not null,
  context        jsonb,
  first_seen_sha text,
  last_seen_sha  text,
  primary key (repo_id, id)
);

create table finding (
  repo_id       bigint not null references repo(id) on delete cascade,
  contract_id   text not null,
  change_id     text not null,
  id            text not null,
  severity      text not null,
  effective     date,
  status        text not null default 'open',
  snoozed_until date,
  issue_number  integer,
  fix_pr_url    text,
  opened_at     timestamptz not null default now(),
  closed_at     timestamptz,
  primary key (repo_id, contract_id, change_id)
);
create index finding_change_idx on finding(change_id);

create table scan_run (
  id                bigserial primary key,
  repo_id           bigint not null references repo(id) on delete cascade,
  sha               text,
  trigger           text not null,
  status            text not null default 'queued',
  engine_version    text,
  knowledge_version text,
  stats             jsonb,
  started_at        timestamptz,
  finished_at       timestamptz,
  error             text,
  created_at        timestamptz not null default now()
);
create index scan_run_queue_idx on scan_run(status, created_at);

create table webhook_delivery (
  id          text primary key,
  received_at timestamptz not null default now()
);
