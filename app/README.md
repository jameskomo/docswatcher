# DocsWatcher app

Spring Boot 4.1 on Java 25. GitHub App webhooks, the scan worker, and the dashboard API. One process, one Postgres. The worker polls the `scan_run` table; there is no queue service.

Read `docs/01-architecture.md` for the data flows this module implements.

## Run locally

```
source ../build-env.sh                       # GraalVM 25 on PATH
docker compose up -d db                      # Postgres on 5432
../mvnw -pl engine -am install -DskipTests   # the engine library the app scans with
../mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=dev
curl localhost:8080/actuator/health
```

The `dev` profile turns off the API token check. Every other profile requires `DOCSWATCHER_API_TOKEN` and rejects requests without `Authorization: Bearer <token>`.

To run the JVM image in compose instead: `../mvnw -pl app spring-boot:build-image -DskipTests` then `docker compose up`.

## Environment

| Variable | Purpose | Default |
|---|---|---|
| `DOCSWATCHER_DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/docswatcher` |
| `DOCSWATCHER_DB_USER`, `DOCSWATCHER_DB_PASSWORD` | Database credentials | `docswatcher` / `docswatcher` |
| `GITHUB_APP_ID` | The GitHub App's numeric ID. When blank, the real GitHub client is not created and webhooks cannot resolve repositories. | blank |
| `GITHUB_APP_PRIVATE_KEY` | The App's private key as a PKCS8 PEM. Convert GitHub's PKCS1 download with `openssl pkcs8 -topk8 -nocrypt -in key.pem`. | blank |
| `GITHUB_WEBHOOK_SECRET` | Shared secret GitHub signs webhook bodies with | blank, which rejects every webhook |
| `GITHUB_API_BASE` | GitHub API base URL, for GitHub Enterprise. Requests pin REST API version `2026-03-10`, so an Enterprise Server must support it (see `docs/10-reference.md`). | `https://api.github.com` |
| `DOCSWATCHER_API_TOKEN` | Bearer token for `/api/**` | blank |
| `DOCSWATCHER_WEB_ORIGIN` | CORS origin for the dashboard | `http://localhost:3000` |
| `DOCSWATCHER_NOTIFY_URL`, `DOCSWATCHER_NOTIFY_TOKEN` | Where early-access requests are emailed from (`notify/`), and its token. Unset: stored only | blank |
| `DOCSWATCHER_KNOWLEDGE_DIR` | A local knowledge checkout instead of the bundled release | bundled |
| `PORT` | HTTP port | `8080` |

Worker tuning lives under `docswatcher.worker` in `application.yaml`: `threads`, `poll-ms`, `enabled`.

## GitHub App setup

1. Create a GitHub App at Settings, Developer settings, GitHub Apps.
2. Webhook URL: `https://<your host>/webhooks/github`. Set a webhook secret and put it in `GITHUB_WEBHOOK_SECRET`.
3. Repository permissions:

   | Permission | Level | Needed for |
   |---|---|---|
   | Contents | Read and write | Cloning to scan needs read. Write is required by the `repository_dispatch` call that triggers a fix, which fails with read only. |
   | Issues | Read and write | Opening, closing and labelling finding issues |
   | Checks | Read and write | The check run posted on each push |
   | Metadata | Read | Mandatory, selected automatically |
   | Pull requests | Read | Only if you later want the app to see the PR a fix opened. Not required today. |
4. Subscribe to events: Installation, Installation repositories, Push, Issues.
5. Generate a private key. GitHub downloads a `.pem` file. Put its contents in
   `GITHUB_APP_PRIVATE_KEY` and the numeric App ID in `GITHUB_APP_ID`. No conversion is
   needed: GitHub's file is PKCS#1 and the app accepts it directly, as well as a PKCS#8
   conversion of the same key.
6. Install the App on an organisation. The installation webhook queues one scan per repository.

Findings arrive as issues labelled `docswatcher` and `docswatcher:<severity>`. Adding these labels to a finding issue acts on it:

| Label | Effect |
|---|---|
| `docswatcher:fix` | Dispatches the fix workflow with the finding's context |
| `docswatcher:snooze-30d` | Snoozes for thirty days |
| `docswatcher:not-in-prod` | Marks the finding informational |

## Fix handoff

Customers add `src/main/resources/templates/docswatcher-fix.yml` to their repository as `.github/workflows/docswatcher-fix.yml` and set the `ANTHROPIC_API_KEY` secret. The app serves the file at `GET /api/setup/workflow`. Pressing Fix, or adding the fix label, sends a `repository_dispatch` event of type `docswatcher-fix` whose `client_payload` holds the finding with its evidence locations (path, line, column; no source text), the migration block, and the guide URL. Claude Code runs in the customer's Actions with the customer's key. DocsWatcher never spends tokens on a fix.

The agent reads code DocsWatcher does not control, so its limits are enforced by the workflow, not the prompt:

- It may edit only the files the finding names, which must be tracked regular files outside `.github/`, and may read only the checkout and the finding. It cannot run commands or reach the web.
- Its job has `contents: read`. The workflow then checks that only the named files changed, with no file created, deleted or re-moded.
- A second job, with no agent and no key, applies the change, checks it again, and opens the pull request.

`FixWorkflowTemplateTest` keeps these properties from regressing.

## API

All under `/api`, JSON, bearer token.

| Method and path | Returns |
|---|---|
| `GET /orgs/{login}/overview` | repo count, contract count, open findings by severity, nearest effective date |
| `GET /orgs/{login}/repos` | repos with open finding counts |
| `GET /orgs/{login}/horizon` | findings grouped by effective month, undated last |
| `GET /orgs/{login}/map` | providers with contract and evidence counts and worst open severity |
| `GET /orgs/{login}/blast-radius/{changeId}` | every repo and finding one change touches |
| `GET /repos/{id}/inventory` | the inventory document rebuilt from stored contracts |
| `GET /repos/{id}/findings` | findings with evidence and change titles |
| `POST /findings/{repoId}/{contractId}/{changeId}/snooze` | body `{"days": 30}` |
| `POST /findings/{repoId}/{contractId}/{changeId}/not-in-prod` | |
| `POST /findings/{repoId}/{contractId}/{changeId}/fix` | dispatches the fix workflow, returns the payload |
| `POST /repos/{id}/rescan` | queues a manual scan |
| `GET /setup/workflow` | the fix workflow YAML |
| `GET /early-access` | early-access requests, newest first |

Outside `/api`, `POST /early-access` takes the Teams page form without a token: validated,
length-limited, a honeypot, 5 requests per client per hour, one row per email. See
`docs/adr/0005-early-access-requests.md`.

Health: `GET /actuator/health`, no token.

## Tests

```
../mvnw -pl app verify
```

Needs Docker for the Postgres Testcontainer. The suite covers webhook signatures, webhook replay from recorded payloads, the scan worker end to end against a local git repository, the dashboard API, and the Flyway schema. The engine is replaced by a deterministic fake behind the `ScanEngine` interface, so these tests do not depend on the engine's detectors.

## Native image

```
../mvnw -Pnative -pl app package -DskipTests
./target/docswatcher-app
```

See the note at the end of this file for the outcome of the last native build on the development machine.

### Last native build outcome

Attempted again on 2026-09-18 with GraalVM Community 25.0.2 and native-maven-plugin 1.1.14. The JVM build and all 31 tests pass. The native image does not yet build. Two blockers were cleared and one remains.

**Cleared: the metadata schema mismatch.** GraalVM 25.0.0 predated the schema the plugin downloads. Installing 25.0.2 fixed it.

**Cleared: the main class was not found.** `spring-boot:repackage` rewrites `target/app.jar` into the `BOOT-INF` layout during the `package` phase, and `native-image` then cannot find the main class at the jar root. This project imports the Spring Boot BOM rather than inheriting the starter parent, so the two steps were never ordered. The `native` profile now skips `repackage`, and the ordinary build is unaffected.

**Remaining: `native-image` itself exits non-zero** during image generation. Not yet diagnosed. The next step is to rerun with `-X` and read the generation log rather than the Maven wrapper error.

The CLI native image does build and run; see `cli/README.md`. Nothing in the app code needs to change for the profile, and the JVM jar is a fine way to run the app in the meantime.
