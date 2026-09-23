-- Early-access requests from the Teams page. docs/adr/0005-early-access-requests.md.
--
-- One row per email address: asking twice updates the request rather than adding a second.
create table early_access (
  email        text        primary key,
  company      text        not null default '',
  repositories text        not null default '',
  providers    text        not null default '',
  interest     text        not null default '',
  message      text        not null default '',
  requests     integer     not null default 1,
  first_at     timestamptz not null default now(),
  last_at      timestamptz not null default now()
);
