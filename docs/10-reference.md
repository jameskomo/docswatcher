# Reference

Commands, endpoints, and settings. Verified on 2026-09-18.

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
| `validate [dir]` | Validates a knowledge directory | 1 on any error, 0 on warnings only |

### Options

| Option | Applies to | Meaning |
|---|---|---|
| `--knowledge <dir>` | all | Use this knowledge directory instead of the bundled release |
| `--format json\|text` | `match` | Output format. Default `json` |
| `--today YYYY-MM-DD` | `scan`, `match` | Date used for day counts. Default is today |
| `--include-low` | `scan`, `match` | Include low-confidence contracts |
| `--repo owner/name` | `scan`, `match` | Repository name recorded in the inventory |
| `--ref <ref>` | `scan`, `match` | Git ref recorded in the inventory |
| `--sha <sha>` | `scan`, `match` | Commit SHA recorded in the inventory |
| `--write-expected` | `scan` | Write `expected-inventory.json` and `expected-findings.json` beside a fixture |
| `-h`, `--help` | all | Help |
| `-V`, `--version` | root | Version |

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
```

Output shapes are defined in `docs/02-schemas.md`. They are not repeated here.

## REST API

Served by the app module. Base path `/api`. All responses are JSON.

### Authentication

A single bearer token from `DOCSWATCHER_API_TOKEN`:

```
curl -H "Authorization: Bearer $DOCSWATCHER_API_TOKEN" localhost:8080/api/orgs/acme/overview
```

`docswatcher.api.require-token` is `true` by default and `false` under the `dev` profile. With the token required but unset, the API refuses requests rather than running open.

### Endpoints

| Method | Path | Returns |
|---|---|---|
| GET | `/api/orgs/{login}/overview` | Repository count, contract count, findings by severity, nearest effective date |
| GET | `/api/orgs/{login}/repos` | Repositories in the installation |
| GET | `/api/orgs/{login}/map` | Providers with contract counts and worst severity |
| GET | `/api/orgs/{login}/horizon` | Findings grouped by the month they take effect |
| GET | `/api/orgs/{login}/blast-radius/{changeId}` | Every repository and finding touched by one deprecation |
| GET | `/api/repos/{id}/inventory` | The stored inventory document for one repository |
| GET | `/api/repos/{id}/findings` | Findings for one repository |
| POST | `/api/repos/{id}/rescan` | Queues a manual scan |
| POST | `/api/findings/{repoId}/{contractId}/{changeId}/snooze` | Snoozes a finding |
| POST | `/api/findings/{repoId}/{contractId}/{changeId}/not-in-prod` | Marks a finding informational |
| POST | `/api/findings/{repoId}/{contractId}/{changeId}/fix` | Dispatches the fix workflow |
| GET | `/api/setup/workflow` | The GitHub Actions workflow a customer installs |
| GET | `/api/setup/workflow.txt` | The same, as plain text |

### Not under `/api`

| Method | Path | Purpose |
|---|---|---|
| POST | `/webhooks/github` | GitHub App events. Verifies `X-Hub-Signature-256`, responds 202 |
| GET | `/actuator/health` | Liveness |

Findings are keyed by the triple of repository, contract, and change, which is why the action endpoints take three path segments. A snooze survives a rescan because of that key.

## Environment variables

Read by the app module. Defaults come from `app/src/main/resources/application.yaml`.

| Variable | Default | Meaning |
|---|---|---|
| `DOCSWATCHER_DB_URL` | `jdbc:postgresql://localhost:5432/docswatcher` | Postgres JDBC URL |
| `DOCSWATCHER_DB_USER` | `docswatcher` | Database user |
| `DOCSWATCHER_DB_PASSWORD` | `docswatcher` | Database password |
| `DOCSWATCHER_API_TOKEN` | empty | Bearer token for the REST API |
| `DOCSWATCHER_WEB_ORIGIN` | `http://localhost:3000` | Origin allowed by CORS |
| `DOCSWATCHER_KNOWLEDGE_DIR` | empty | Knowledge directory. Empty means the bundled release |
| `GITHUB_APP_ID` | empty | GitHub App numeric id |
| `GITHUB_APP_PRIVATE_KEY` | empty | App private key, PKCS8 PEM |
| `GITHUB_WEBHOOK_SECRET` | empty | Shared secret for signature verification |
| `GITHUB_API_BASE` | `https://api.github.com` | Override for GitHub Enterprise |
| `PORT` | `8080` | HTTP port |

Worker settings live under `docswatcher.worker` in the YAML: `enabled` true, `threads` 2, `poll-ms` 2000, `clone-timeout-seconds` 120. The worker runs on virtual threads.

Label names are configurable and default to `docswatcher:fix`, `docswatcher:snooze-30d`, and `docswatcher:not-in-prod`.

The web site reads `NUXT_PUBLIC_RELAY_URL`. Leave it empty to use jsDelivr and the GitHub API instead of a relay.

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
| `npm run bundle` | Regenerates `generated/knowledge.json` and `generated/samples.json` |
| `npm run serve-export` | Serves the export, by default under a deep subpath |

Also `node scripts/vendor-repos.mjs --refresh`, which re-downloads the bundled real repositories at their pinned commits.

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
