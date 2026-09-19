# Architecture

DocsWatcher scans a repository for every external contract it depends on, matches those contracts against a knowledge base of provider deprecations, and turns each match into a finding with a due date and a fix. This document describes the pieces, how they connect, and what v1 leaves out.

Read `docs/02-schemas.md` first. Every arrow in this document carries one of the schemas defined there.

## The one seam

Everything meets at the inventory document. The engine produces it. The matcher consumes it. The app stores it. The dashboard renders it. The CLI prints it. Two engines exist, one in Java and one in TypeScript, and they are interchangeable because they emit the same document from the same detector data.

```
knowledge base ──(detectors.yaml)──► engine ──(inventory.json)──► matcher ──(findings)──► surfaces
      │                                                              ▲
      └────────────────────────(changes/*.yaml)──────────────────────┘
```

No component depends on another through code. They depend on each other through data, and the parity test in `docs/04-test-plan.md` keeps that data identical across engines.

## The pieces

| Piece | Language | Runs where | Job |
|---|---|---|---|
| Knowledge base | YAML | Git repository, open source | Providers, detectors, change records, fixtures. The moat. |
| Java engine | Java 25 | CLI, app worker | Loads detectors, scans a checkout, emits an inventory. No Spring dependency. |
| TypeScript engine | TypeScript | Browser | Same job, same data, same output. Runs tree-sitter through web-tree-sitter. |
| Matcher | Java and TypeScript | Wherever an engine runs | Pure function from inventory plus active changes to findings. |
| CLI | Java 25, picocli, GraalVM native-image | Developer machines, CI | Wraps engine and matcher. Prints inventory and findings. Exit code reflects breaking findings. |
| App | Java 25, Spring Boot 4, GraalVM native-image | One container | GitHub App webhooks, scan worker, findings store, API for the dashboard. |
| Web | Nuxt, static export | Cloudflare Pages or GitHub Pages | Public site with browser scanner and deprecation calendar. Logged-in dashboard. |
| Relay | Cloudflare Worker | Cloudflare free tier | Streams a repo tarball to the browser with permissive cross-origin headers. See ADR 0003. |
| Fix handoff | GitHub Actions workflow | The target repository | Runs an automated coding agent action with a prompt assembled from the finding. Opens the PR. |

The fix handoff runs in the target repository's own GitHub Actions environment with repository-scoped secrets. DocsWatcher assembles the remediation context, test coordinates, and instructions, and triggers the workflow. This keeps all source code and execution strictly inside the developer's own CI environment.

## Maven layout

One multi-module build. One language for everything that runs on a server.

```
docswatcher/
  pom.xml
  knowledge/     YAML providers, changes, detectors, fixtures. Validators and spec generators.
  engine/        Plain library. Detectors, inventory model, matcher. No Spring.
  cli/           picocli over engine. Native-image profile.
  app/           Spring Boot 4. Webhooks, API, scan worker, Postgres. Native-image profile.
  web/           Nuxt. Public site, TypeScript engine, dashboard. Static export.
  relay/         Cloudflare Worker. Tarball relay. Not a Maven module, lives here for one clone.
```

The `engine` module has no Spring dependency on purpose. It is the piece contributors run, the piece the CLI ships, and the piece the benchmark corpus tests. The `knowledge` module is published as a versioned artifact and as a plain directory, so both engines consume the same release.

## CLI commands

The CLI is the contributor's and CI's view of the engine. Three commands, stable from v1.

| Command | Does | Exit code |
|---|---|---|
| `docswatcher scan <path>` | Runs the engine on a checkout and prints the inventory document | 0 |
| `docswatcher match <path>` | Runs scan, then the matcher against the bundled knowledge release, and prints findings | 1 if any breaking finding is open, else 0 |
| `docswatcher validate` | Runs the knowledge base validators on the bundled or a given knowledge directory | 1 on any violation |

`scan` accepts `--write-expected` to write `expected-inventory.json` and `expected-findings.json` next to a fixture. CI never uses it. `--knowledge <dir>` on any command points at a local knowledge checkout instead of the bundled release. `--format json` is the default and `--format text` is for humans.

## The three surfaces

**Public site.** Anyone pastes a GitHub URL. The browser fetches the tarball through the relay, runs the TypeScript engine locally, and renders the inventory and findings. The deprecation calendar is generated at build time from the knowledge base. Nothing runs on a server except the relay.

**Logged-in app.** GitHub login. Org overview, the map of every external contract, the horizon of upcoming effective dates, an inventory browser, a finding page with evidence and a fix button, settings for snooze and production flags. Nuxt pages calling the app's API.

**GitHub App.** Installed on an org. Check runs on every push with the inventory summary. One issue per finding with severity label and due date. A fix label that triggers the handoff workflow.

## Data flows

```
                       ┌──────────────┐
   install / push ───► │  GitHub App  │
                       │  webhooks    │
                       └──────┬───────┘
                              ▼
                       ┌──────────────┐    shallow clone    ┌──────────────┐
                       │  scan_run    │ ──────────────────► │ Java engine  │
                       │  queued      │                     │ + matcher    │
                       └──────┬───────┘                     └──────┬───────┘
                              │                                    │ inventory + findings
                              ▼                                    ▼
                       ┌──────────────┐                     ┌──────────────┐
                       │  Postgres    │ ◄────────────────── │  persist     │
                       │  contracts   │                     └──────────────┘
                       │  findings    │
                       └──────┬───────┘
                              │
              ┌───────────────┼──────────────────┐
              ▼               ▼                  ▼
        check run       finding issue       dashboard API
        on the push     with due date       map, horizon, blast radius
                              │
                              │  fix label
                              ▼
                       ┌──────────────┐    customer's key    ┌──────────────┐
                       │ dispatch     │ ───────────────────► │ Claude Code  │
                       │ workflow     │                      │ action → PR  │
                       └──────────────┘                      └──────────────┘
```

### Install scan

1. GitHub sends the installation webhook with the list of repos.
2. The app creates an `installation` row, one `repo` row per repository, and one `scan_run` per repo with status `queued`.
3. The worker picks up queued runs on virtual threads. Each run does a shallow clone at the default branch head, runs the Java engine, runs the matcher against active change records, and writes contracts and findings.
4. The app posts one check run per repo on the default branch head summarising the inventory, and one issue per finding.

### Push rescan

1. The push webhook arrives with the changed file list.
2. The app queues a `scan_run` with the head SHA and the changed paths.
3. The worker rescans the whole repo. Incremental scanning is a later optimisation. The clone is shallow and the engine is fast, so full rescans are fine for v1.
4. Contracts are upserted by `id`. Contracts no longer observed are marked `last_seen` at the previous SHA and their findings close as `fixed` if the commit removed the evidence.
5. The check run on the push reports new, resolved, and unchanged findings.

### Knowledge base update rematch

1. A new knowledge base release is published.
2. The app records the version and queues a rematch for every repo. A rematch runs the matcher against stored contracts. It does not rescan.
3. New findings open issues. Findings whose change record moved to `withdrawn` close. Findings whose change record moved to `expired` stay open with negative days remaining.
4. If the release changes detectors and not only changes, a full rescan is queued instead.

### Fix label to PR

1. A user adds the fix label to a finding issue, or clicks fix in the dashboard.
2. The app assembles the context: the finding, every evidence location with a snippet, the change record's migration block, and the migration guide fetched and inlined.
3. The app dispatches a workflow in the customer's repository. The workflow is a file DocsWatcher offers to add on install. It runs a Claude Code action with the customer's API key from their repository secrets.
4. The action edits the code, runs the affected tests, and opens a PR. The PR body links back to the finding.
5. The app records the PR URL on the finding. When the PR merges and the next push rescan no longer observes the contract, the finding closes as `fixed`.

## Data model

Five tables. The knowledge base is not stored per customer. Change records are read from the released knowledge base at match time.

**installation**

| Column | Notes |
|---|---|
| id | GitHub installation ID |
| account_login | Org or user |
| knowledge_version | Last version rematched against |
| created_at, suspended_at | |

**repo**

| Column | Notes |
|---|---|
| id | GitHub repository ID |
| installation_id | |
| full_name | `owner/name` |
| default_branch | |
| last_scanned_sha | |
| production | Boolean. Off means findings are informational. Set in the dashboard. |

**contract**

| Column | Notes |
|---|---|
| id | The contract `id` from the inventory document |
| repo_id | |
| provider, kind, key, confidence | Copied from the inventory |
| evidence | JSON array from the inventory |
| context | JSON from the inventory |
| first_seen_sha, last_seen_sha | |

Primary key is `repo_id` plus `id`.

**finding**

| Column | Notes |
|---|---|
| id | The finding `id` from the schemas document |
| repo_id | |
| contract_id | |
| change_id | Knowledge base change record ID |
| severity, effective | Copied at match time |
| status | `open`, `snoozed`, `not_in_prod`, `fixed` |
| snoozed_until | |
| issue_number | GitHub issue |
| fix_pr_url | |
| opened_at, closed_at | |

Primary key is `repo_id` plus `contract_id` plus `change_id`. Snoozes survive rescans because they key on this triple.

**scan_run**

| Column | Notes |
|---|---|
| id | |
| repo_id | |
| sha | |
| trigger | `install`, `push`, `rematch`, `manual` |
| status | `queued`, `running`, `done`, `failed` |
| engine_version, knowledge_version | |
| stats | JSON from the inventory |
| started_at, finished_at, error | |

The worker polls this table. There is no queue service. A single `SELECT ... FOR UPDATE SKIP LOCKED` on queued rows is enough for one container and stays enough for several.

## Excluded from v1

| Excluded | Why deferred | Target milestone |
|---|---|---|
| Runtime layer, observing Deprecation and Sunset headers in traffic | Requires an in-path telemetry exporter | v2, as an OpenTelemetry processor |
| Email and Slack alerts | GitHub issues and PRs natively provide notifications | Post-v1 notification plugin system |
| GitLab, Bitbucket | Focusing on GitHub ecosystem first | Multi-VCS milestone |
| Proprietary coding agent | Integrates with existing coding agents via CI actions | Plug-and-play agent integration |
| Incremental scanning on push | Full rescans are fast enough on shallow clones | Optimized diff scanning for monorepos |

## What is deliberately simple

- The matcher is a pure function. Every finding can be recomputed from stored contracts and the knowledge base. Nothing about a finding is stored that cannot be re-derived except its status and its PR link.
- One container runs webhooks, API, and worker. Splitting them is a config change, not a code change, because the worker is a polling loop over a table.
- The dashboard has two views done well, the map and the horizon. Blast radius across an org arrives once an org has installed on many repos.
