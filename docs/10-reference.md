# Reference

Commands, endpoints, and settings. Verified on 2026-09-23.

## Command line

Run as a jar or as the native binary. Both behave identically.

```
java -jar cli/target/docswatcher-cli.jar <command> [options]
cli/target/docswatcher <command> [options]
```

### Commands

| Command | Does | Exit code |
|---|---|---|
| `scan <path>` | Scans a checkout and prints the inventory document | 0 |
| `match <path>` | Scans, matches against the knowledge base, prints findings | 1 if any breaking finding is open, else 0 |
| `validate [dir]` | Validates a knowledge directory, or your own API records: a directory named `.docswatcher`, every `--knowledge-extra`, or with no `dir`, `./.docswatcher` when there is one. See `docs/19-your-own-apis.md` | 1 on any error, 0 on warnings only |
| `mcp` | Serves the Model Context Protocol on stdio until the client closes it. See `docs/14-coding-agents.md` | 0 |

### Options

| Option | Applies to | Meaning |
|---|---|---|
| `--knowledge <dir>` | all | Use this knowledge directory instead of the bundled release |
| `--knowledge-extra <dir>` | all | Add a directory of your own API records (`providers/internal-<name>/...`), such as a checkout of your organisation's `.docswatcher` repository's `.docswatcher/`. Repeatable. The scanned repository's own `.docswatcher/` (for `mcp`, the working directory's) is always read. Invalid records stop `scan` and `match` with exit 3. See `docs/19-your-own-apis.md` |
| `--format json\|text` | `match` | Output format. Default `json` |
| `--report <file>` | `match` | Also write the findings as JSON to this file, whatever `--format` prints. Text and JSON from one scan |
| `--inventory <file>` | `match` | Also write the inventory document, as `scan` prints it, to this file |
| `--today YYYY-MM-DD` | `scan`, `match`, `mcp` | Date used for day counts. Default is today, asked afresh on every `mcp` call |
| `--include-low` | `scan`, `match` | Include low-confidence contracts |
| `--exclude <pattern>` | `scan`, `match` | Skip paths matching a `.gitignore`-style pattern, after the repository's `.gitignore` files and `.docswatcherignore`. Repeatable. See `docs/18-excluding-paths.md` |
| `--max-files <n>` | `scan`, `match` | Stop reading after this many files (after exclusions). Default 20000. See "Scan limits" |
| `--max-total-mb <n>` | `scan`, `match` | Stop reading once this many megabytes have been read. Default 200 |
| `--max-seconds <n>` | `scan`, `match` | Stop scanning further files after this many seconds. Default 600 |
| `--repo owner/name` | `scan`, `match` | Repository name recorded in the inventory |
| `--ref <ref>` | `scan`, `match` | Git ref recorded in the inventory |
| `--sha <sha>` | `scan`, `match` | Commit SHA recorded in the inventory |
| `--write-expected` | `scan` | Write `expected-inventory.json` and `expected-findings.json` beside a fixture |
| `-h`, `--help` | all | Help |
| `-V`, `--version` | root | CLI release version. The knowledge base version (`knowledge/VERSION`) is printed by `validate` |

### Examples

```
# Human-readable drift report, clock pinned for reproducibility
docswatcher match /path/to/repo --knowledge knowledge --format text --today 2026-09-18

# Machine-readable, for a CI step or a script
docswatcher match /path/to/repo --format json > findings.json

# Inventory only, no matching
docswatcher scan /path/to/repo --knowledge knowledge

# Check the knowledge base
docswatcher validate knowledge

# Check your own API records, then scan with them and your organisation's
docswatcher validate .docswatcher
docswatcher match . --knowledge-extra ../org-records/.docswatcher --format text

# Let a coding agent ask before it writes a model ID
claude mcp add docswatcher -- docswatcher mcp
```

Output shapes are defined in `docs/02-schemas.md`. They are not repeated here.

### GitHub Action inputs

`path`, `fail-on`, `include-low`, `exclude`, `knowledge`, `report` and `version`, described in
`docs/11-ci-integration.md`. `knowledge` takes directories of your own API records, one per line,
and passes each as `--knowledge-extra`.

## REST API

Served by the app module. Base path `/api`. All responses are JSON.

### Authentication

Two principals (ADR 0008).

**The owner** holds the bearer token from `DOCSWATCHER_API_TOKEN` and sees everything:

```
curl -H "Authorization: Bearer $DOCSWATCHER_API_TOKEN" localhost:8080/api/orgs/acme/overview
```

A request that presents an `Authorization` header is judged on the token alone. `docswatcher.api.require-token` is `true` by default and `false` under the `dev` profile. If the token is required but unset, requests that present one get 503 rather than being let through.

**A member** is signed in with GitHub and presents the `__Host-docswatcher_session` cookie. A member sees only the organisations and repositories GitHub listed for them at sign-in, and can reach only endpoints marked `@MemberAccess` (the Member column below). Every other endpoint answers a member with 403. An organisation or repository outside the member's access answers 404. Organisation-wide answers count only the member's repositories. POST endpoints need write, maintain or admin on the repository. They also need an `Origin` equal to `DOCSWATCHER_WEB_ORIGIN`, or `Sec-Fetch-Site: same-origin`.

A request with neither a token nor a live session gets 401.

### Endpoints

| Method | Path | Returns | Member |
|---|---|---|---|
| GET | `/api/orgs/{login}/overview` | Repository count, contract count, findings by severity, nearest effective date | read, filtered |
| GET | `/api/orgs/{login}/repos` | Repositories in the installation | read, filtered |
| GET | `/api/orgs/{login}/map` | Providers with contract counts and worst severity | read, filtered |
| GET | `/api/orgs/{login}/horizon` | Findings grouped by the month they take effect | read, filtered |
| GET | `/api/orgs/{login}/blast-radius/{changeId}` | Every repository and finding touched by one deprecation | read, filtered |
| GET | `/api/repos/{id}/inventory` | The stored inventory document for one repository | read |
| GET | `/api/repos/{id}/findings` | Findings for one repository | read |
| GET | `/api/repos/{id}/runtime` | Runtime observations for one repository | read |
| POST | `/api/repos/{id}/rescan` | Queues a manual scan | write |
| POST | `/api/repos/{id}/findings/snooze` | Snoozes the finding named in the body `{"contract", "change", "days"}`; `days` defaults to 30 | write |
| POST | `/api/repos/{id}/findings/not-in-prod` | Marks the finding named in the body `{"contract", "change"}` informational | write |
| POST | `/api/repos/{id}/findings/fix` | Dispatches the fix workflow for the finding named in the body | write |
| POST | `/api/findings/{repoId}/{contractId}/{changeId}/snooze` | Snoozes a finding | write |
| POST | `/api/findings/{repoId}/{contractId}/{changeId}/not-in-prod` | Marks a finding informational | write |
| POST | `/api/findings/{repoId}/{contractId}/{changeId}/not-affected` | Marks a finding not affected: the change does not touch this code | write |
| POST | `/api/findings/{repoId}/{contractId}/{changeId}/fix` | Dispatches the fix workflow | write |
| GET | `/api/setup/workflow` | The GitHub Actions workflow a customer installs | yes |
| GET | `/api/setup/workflow.txt` | The same, as plain text | yes |
| GET | `/api/early-access` | Early-access requests from the Teams page, newest first | no |
| POST | `/api/runtime/otlp/v1/traces` | OTLP/JSON trace ingest (docs/13-runtime-observation.md) | no |

### Not under `/api`

| Method | Path | Purpose |
|---|---|---|
| POST | `/webhooks/github` | GitHub App events. Verifies `X-Hub-Signature-256`, responds 202 |
| POST | `/early-access` | The Teams page form. Public: validated, rate-limited per client, honeypot-checked; one row per email (ADR 0005) |
| GET | `/auth/github/login` | Starts sign-in with GitHub: a 302 to GitHub with a state and a PKCE challenge, both remembered in `__Host-docswatcher_oauth`. 503 when sign-in is not configured (ADR 0008) |
| GET | `/auth/github/callback` | GitHub returns here. Checks the state, exchanges the code, records the person's access, sets `__Host-docswatcher_session`, and redirects to `/#/app`. On failure it redirects to `/#/app?signin=denied`, `failed` or `unavailable` |
| GET | `/auth/me` | `{enabled, signedIn, user, orgs, expiresAt}`. Always 200, never cached |
| POST | `/auth/logout` | Ends the session and clears the cookie. 204. Same-origin only |
| GET | `/actuator/health` | Liveness |

Findings are keyed by the triple of repository, contract, and change. A snooze survives a rescan because of that key. The three-segment action routes carry the key in the path. That fails for a contract id containing a slash, such as `stripe:endpoint:POST /v1/sources`, so the dashboard uses the `/api/repos/{id}/findings/...` routes, which take the contract and change in the body.

## Environment variables

Read by the app module. Defaults come from `app/src/main/resources/application.yaml`.

| Variable | Default | Meaning |
|---|---|---|
| `DOCSWATCHER_DB_URL` | `jdbc:postgresql://localhost:5432/docswatcher` | Postgres JDBC URL |
| `DOCSWATCHER_DB_USER` | `docswatcher` | Database user |
| `DOCSWATCHER_DB_PASSWORD` | `docswatcher` | Database password |
| `DOCSWATCHER_API_TOKEN` | empty | Bearer token for the REST API |
| `DOCSWATCHER_WEB_ORIGIN` | `http://localhost:3000` | The site's public origin: allowed by CORS, required as `Origin` on a member's POST, and the base of the sign-in callback (`{origin}/auth/github/callback`) and the post-sign-in redirect. In production, `https://docswatcher.vukisha.co.ke` |
| `DOCSWATCHER_KNOWLEDGE_DIR` | empty | Knowledge directory. Empty means the bundled release |
| `GITHUB_APP_ID` | empty | GitHub App numeric id |
| `GITHUB_APP_PRIVATE_KEY` | empty | App private key, PKCS8 PEM |
| `GITHUB_WEBHOOK_SECRET` | empty | Shared secret for signature verification |
| `GITHUB_API_BASE` | `https://api.github.com` | Override for GitHub Enterprise |
| `GITHUB_CLIENT_ID` | empty | The GitHub App's client id, for sign-in (`docswatcher.github.client-id`) |
| `GITHUB_CLIENT_SECRET` | empty | The App's client secret. In production it is a file secret, `docswatcher.github.client-secret`, which takes precedence. Either value blank: sign-in answers 503 |
| `GITHUB_WEB_BASE` | `https://github.com` | Where sign-in happens and the code is exchanged. Override for GitHub Enterprise |
| `DOCSWATCHER_NOTIFY_URL` | empty | The `notify/` Worker that emails each early-access request. Empty: stored, not emailed |
| `DOCSWATCHER_NOTIFY_TOKEN` | empty | Bearer token for that Worker. In production it is a file secret, `docswatcher.notify.token` |
| `PORT` | `8080` | HTTP port |

Worker settings live under `docswatcher.worker` in the YAML: `enabled` true, `threads` 2, `poll-ms` 2000, `clone-timeout-seconds` 120. The worker runs on virtual threads. Scan limits live under `docswatcher.scan`: `max-files` 20000, `max-total-mb` 200, `max-seconds` 600 (see "Scan limits").

`docswatcher.github.session-ttl` (default `8h`) sets how long a signed-in session lasts.

Label names are configurable under `docswatcher.github` (`fix-label`, `snooze-label`, `not-in-prod-label`, `not-affected-label`) and default to:

| Label | Finding status | Effect |
|---|---|---|
| `docswatcher:fix` | unchanged | Dispatches the fix workflow |
| `docswatcher:snooze-30d` | `snoozed` | Hidden for thirty days |
| `docswatcher:not-in-prod` | `not_in_prod` | Informational: the code does not run in production |
| `docswatcher:not-affected` | `not_affected` | The change does not touch this code; no longer fails the check run |

Closing a finding's issue by hand also sets `not_affected`; reopening it sets `open`. Closes sent by the App's own bot account (it closes an issue when the evidence disappears) are ignored. Every label, close and reopen needs write permission or above from the sender, and fails closed. All four statuses survive rescans; a finding whose evidence disappears becomes `fixed` whatever its status.

The web site reads `NUXT_PUBLIC_RELAY_URL`. Leave it empty to use jsDelivr and the GitHub API instead of a relay.

## GitHub REST API version

Every call to the GitHub REST API sends `X-GitHub-Api-Version: 2026-03-10`: the GitHub App (`RestGitHubClient.API_VERSION`), the browser's API fallback (`GITHUB_API_VERSION` in `web/app/utils/fetchRepo.ts`) and the tarball relay (`relay/src/worker.js`). The previous pin, 2022-11-28, stops being served on 2028-03-10, and GitHub answers a retired version with `410 Gone`.

None of 2026-03-10's breaking changes touch what DocsWatcher reads. The app reads `token` and `expires_at` from installation tokens, `id`, `full_name` and `default_branch` from installation repositories, `number` from a created issue, and `permission` from a collaborator's permission. Sign-in (`GitHubUserApi`) reads `access_token` from the code exchange; `id`, `login`, `name` and `avatar_url` from `/user`; `id` and `account.login` from `/user/installations`; and `id`, `full_name` and `permissions` from `/user/installations/{id}/repositories`. The browser reads `default_branch`, and a tree's `sha`, `path`, `type` and `size`. The relay only follows the tarball redirect. The removed fields (`assignee`, `has_downloads`, `use_squash_pr_title_as_default` and the rest) are not read anywhere. A `permission` outside `admin`, `maintain`, `write`, `triage`, `read` and `none` counts as `none`.

GitHub Enterprise Server only accepts versions it knows. A server that predates 2026-03-10 rejects the header with `400`, so `GITHUB_API_BASE` needs a release that supports it.

To move to a newer version, read GitHub's [breaking changes](https://docs.github.com/en/rest/about-the-rest-api/breaking-changes) against the fields above, change the three constants, and update the tests that assert them.

## Maven commands

Always `source build-env.sh` first.

| Command | Does |
|---|---|
| `./mvnw verify` | Builds and tests every module |
| `./mvnw -q -pl knowledge,engine,cli -am install -DskipTests` | Fast path to a working CLI |
| `./mvnw -pl app verify` | App tests only. Needs Docker |
| `./mvnw -Pnative -pl cli -am package` | Native CLI binary at `cli/target/docswatcher` |
| `./mvnw -Pnative -pl app package -DskipTests` | Native app image. See `docs/09-status.md` for its current state |
| `./mvnw -pl engine -am verify -Dgroups=nightly` | The performance budget test |

## npm scripts

In `web/`:

| Script | Does |
|---|---|
| `npm run dev` | Development server on port 3000 |
| `npm run build` | Production build |
| `npm run generate` | Static export to `.output/public`, then rewrites assets to relative URLs |
| `npm run preview` | Serves the production build |
| `npm test` | Vitest unit tests for the TypeScript engine |
| `npm run e2e` | Playwright browser tests |
| `npm run parity` | Compares the TypeScript engine against the Java engine's expected files |
| `npm run bundle` | Regenerates `generated/knowledge.json`, `generated/samples.json` and the feeds in `public/feeds/` |
| `npm run serve-export` | Serves the export, by default under a deep subpath |

Also `node scripts/vendor-repos.mjs --refresh`, which re-downloads the bundled real repositories at their pinned commits, and `node scripts/watch-sources.mjs --state <dir> [--report <file>]`, one knowledge watch run (`docs/16-knowledge-watch.md`).

In `relay/`:

| Script | Does |
|---|---|
| `npm test` | Unit tests against a fake fetch. No network |
| `npm run dev` | Local worker via wrangler |
| `npm run deploy` | Deploys to Cloudflare |

## Knowledge scripts

| Script | Does |
|---|---|
| `knowledge/scripts/validate` | Schema, dates, regex dialect, fixture coverage |
| `knowledge/scripts/parity` | Runs the browser engine against every fixture and compares to the Java engine |

## Study scripts

| Script | Does |
|---|---|
| `study/run.sh <cohort> <results> [date]` | Clones and scans every repository in a cohort. `DOCSWATCHER` names the command, `JOBS` the parallelism |
| `node study/summarize.mjs <results> <cohort> [--exclude review.json]` | Writes the cohort's public `summary.json` and `repos.txt`: counts only, no repository named |

See `docs/17-open-source-study.md`.

## Public feeds

Static files served by the site, rebuilt from the knowledge base on every build. `docs/15-feeds-and-sharing.md`.

| Path | Is |
|---|---|
| `/feeds/deprecations.ics` | iCalendar of every dated shutdown |
| `/feeds/<provider>.ics` | One provider's shutdowns |
| `/feeds/deprecations.atom` | Atom feed, newest announcement first |
| `/feeds/deprecations.json` | Every live change record, with CORS open |
| `/#/?repo=owner/name` | Opens the scanner and scans that public repository |

## Relay endpoints

| Path | Returns |
|---|---|
| `GET /health` | `{"ok": true, "service": "docswatcher-relay"}` |
| `GET /tarball/:owner/:repo` | Tarball of the default branch, as `application/gzip` |
| `GET /tarball/:owner/:repo/:ref` | Tarball at a branch, tag, or SHA |

An `Authorization` header is forwarded to GitHub and never cached, which is how private repositories work. Anonymous responses are cached five minutes. Tarballs over 60 MB are refused with 413.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success. For `match`, no breaking finding is open |
| 1 | For `match`, at least one breaking finding is open. For `validate`, at least one error |
| 2 | Usage error, such as a missing argument |
| 3 | The command did not complete: the path is not a directory, your own API records are invalid (every error is on stderr), the scan threw, or the JVM ran out of memory. Never a result, always a failure |
| 4 | For `scan` and `match`, a scan limit stopped the scan and, for `match`, nothing breaking was found in the files it read. The output is valid for those files, but it is not a clean result for the repository. A breaking finding still exits 1. A warning on stderr names the limit and the number of files not scanned |

### Scan limits

Each file is limited on its own: over 1 MB it is skipped, and parsing and querying it stop after 10 seconds. The whole scan is limited too, because every file read is held in memory until the scan ends:

| Limit | Default | CLI | App (`docswatcher.scan`) | Recorded as |
|---|---|---|---|---|
| Files read | 20,000 | `--max-files` | `max-files` | `maxFiles` |
| Bytes read | 200 MB | `--max-total-mb` | `max-total-mb` | `maxBytes` |
| Time, reading and all three layers | 10 minutes | `--max-seconds` | `max-seconds` | `maxDuration` |

Files are read in path order. At a limit, no further file is read (or, when time runs out after reading, searched by the call-site layer), and the inventory's `stats.incomplete` says which limit, its value, and how many files were not scanned. A file already being parsed finishes within its own 10 seconds, so the time limit can be overrun by that much. A complete scan has no `incomplete` field. The first remedy is excluding what needs no scan (`.docswatcherignore`, `--exclude`); raising a limit is the second.

In the GitHub App an incomplete scan closes no finding and no issue: contracts it did not see are carried forward from the previous scan, because they may be in the files it skipped. Its check run is titled `Incomplete scan: ...`, explains the stop in its summary, and is never `success`; without a breaking finding it is `neutral`. The web scanner has its own limit of 300 files, chosen by relevance, and says when it applied it.
