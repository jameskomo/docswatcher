# DocWatcher app

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

The `dev` profile turns off the API token check. Every other profile requires `DOCWATCHER_API_TOKEN` and rejects requests without `Authorization: Bearer <token>`.

To run the JVM image in compose instead: `../mvnw -pl app spring-boot:build-image -DskipTests` then `docker compose up`.

## Environment

| Variable | Purpose | Default |
|---|---|---|
| `DOCWATCHER_DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/docwatcher` |
| `DOCWATCHER_DB_USER`, `DOCWATCHER_DB_PASSWORD` | Database credentials | `docwatcher` / `docwatcher` |
| `GITHUB_APP_ID` | The GitHub App's numeric ID. When blank, the real GitHub client is not created and webhooks cannot resolve repositories. | blank |
| `GITHUB_APP_PRIVATE_KEY` | The App's private key as a PKCS8 PEM. Convert GitHub's PKCS1 download with `openssl pkcs8 -topk8 -nocrypt -in key.pem`. | blank |
| `GITHUB_WEBHOOK_SECRET` | Shared secret GitHub signs webhook bodies with | blank, which rejects every webhook |
| `GITHUB_API_BASE` | GitHub API base URL, for GitHub Enterprise | `https://api.github.com` |
| `DOCWATCHER_API_TOKEN` | Bearer token for `/api/**` | blank |
| `DOCWATCHER_WEB_ORIGIN` | CORS origin for the dashboard | `http://localhost:3000` |
| `DOCWATCHER_KNOWLEDGE_DIR` | A local knowledge checkout instead of the bundled release | bundled |
| `PORT` | HTTP port | `8080` |

Worker tuning lives under `docwatcher.worker` in `application.yaml`: `threads`, `poll-ms`, `enabled`.

## GitHub App setup

1. Create a GitHub App at Settings, Developer settings, GitHub Apps.
2. Webhook URL: `https://<your host>/webhooks/github`. Set a webhook secret and put it in `GITHUB_WEBHOOK_SECRET`.
3. Repository permissions: Contents read, Checks write, Issues write, Metadata read.
4. Subscribe to events: Installation, Installation repositories, Push, Issues.
5. Generate a private key, convert it to PKCS8, and set `GITHUB_APP_PRIVATE_KEY` and `GITHUB_APP_ID`.
6. Install the App on an organisation. The installation webhook queues one scan per repository.

Findings arrive as issues labelled `docwatcher` and `docwatcher:<severity>`. Adding these labels to a finding issue acts on it:

| Label | Effect |
|---|---|
| `docwatcher:fix` | Dispatches the fix workflow with the finding's context |
| `docwatcher:snooze-30d` | Snoozes for thirty days |
| `docwatcher:not-in-prod` | Marks the finding informational |

## Fix handoff

Customers add `src/main/resources/templates/docwatcher-fix.yml` to their repository as `.github/workflows/docwatcher-fix.yml` and set the `ANTHROPIC_API_KEY` secret. The app serves the file at `GET /api/setup/workflow`. Pressing Fix, or adding the fix label, sends a `repository_dispatch` event of type `docwatcher-fix` whose `client_payload` holds the finding, every evidence location with a snippet, the migration block, and the guide URL. The workflow runs `anthropics/claude-code-action@v1` in the customer's Actions with the customer's key. DocWatcher never spends tokens on a fix.

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

Health: `GET /actuator/health`, no token.

## Tests

```
../mvnw -pl app verify
```

Needs Docker for the Postgres Testcontainer. The suite covers webhook signatures, webhook replay from recorded payloads, the scan worker end to end against a local git repository, the dashboard API, and the Flyway schema. The engine is replaced by a deterministic fake behind the `ScanEngine` interface, so these tests do not depend on the engine's detectors.

## Native image

```
../mvnw -Pnative -pl app package -DskipTests
./target/docwatcher-app
```

See the note at the end of this file for the outcome of the last native build on the development machine.

### Last native build outcome

Attempted on 2026-09-18 with GraalVM Community 25+37.1 and native-maven-plugin 1.1.14. The JVM build and all 26 tests pass. The native build failed before compiling, with this message from the plugin:

```
The configured GraalVM reachability metadata repository provides a reachability-metadata schema,
but your GraalVM installation at ~/.jdks/graalvm-community-openjdk-25+37.1 does not.
Please update your GraalVM installation to a newer version. Update to the latest 25 release.
```

The metadata repository the plugin downloads now uses a schema that GraalVM 25.0.0 predates. The fix is to install the latest GraalVM 25 patch release and point `build-env.sh` at it, then rerun the command above. Pinning an older metadata release is not an option because the plugin's download endpoint only serves the current line. Nothing in the app code needed a change for the native profile; the profile stays in the pom and runs unchanged once the JDK is updated.
