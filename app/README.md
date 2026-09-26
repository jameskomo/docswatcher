# DocsWatcher app

Spring Boot 4.1 on Java 25. GitHub App webhooks, the scan worker, and the dashboard API. One server process, one Postgres, and a short-lived process per scan. The worker polls the `scan_run` table; there is no queue service.
Spring Boot 4.1 on Java 25. GitHub App and GitLab webhooks, the scan worker, and the dashboard API. One process, one Postgres. The worker polls the `scan_run` table; there is no queue service.

Read `docs/01-architecture.md` for the data flows this module implements.

## Run locally

```
source ../build-env.sh                       # GraalVM 25 on PATH
docker compose up -d db                      # Postgres on 5432
../mvnw -pl engine -am install -DskipTests   # the engine library the app scans with
../mvnw -pl app spring-boot:run -Dspring-boot.run.profiles=dev
curl localhost:8080/actuator/health
```

The `dev` profile turns off the API token check. Every other profile requires either `Authorization: Bearer <DOCSWATCHER_API_TOKEN>` (the owner, sees everything) or a signed-in session cookie (a member, sees what GitHub or GitLab lets them see). See "Sign-in with GitHub" and "GitLab setup" below.

For the organisation dashboard locally, run the site with `npm run dev` in `web/`. It proxies `/api` and `/auth` to this app on :8080, so the browser sees one origin, as it does behind nginx. Set `DOCSWATCHER_WEB_ORIGIN=http://localhost:3000` and add `http://localhost:3000/auth/github/callback` as a second callback URL on a development App.

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
| `GITHUB_CLIENT_ID` | The App's client id, for sign-in with GitHub | blank, which turns sign-in off |
| `GITHUB_CLIENT_SECRET` | The App's client secret. In production it is a file secret instead: `/run/secrets/docswatcher.github.client-secret`, which takes precedence | blank, which turns sign-in off |
| `GITHUB_WEB_BASE` | Where people sign in, for GitHub Enterprise | `https://github.com` |
| `GITLAB_BASE_URL` | The GitLab instance: gitlab.com or a self-managed one's web address. Sign-in, clones and API calls all go there | `https://gitlab.com` |
| `GITLAB_CLIENT_ID` | The GitLab OAuth application's id, for sign-in with GitLab | blank, which turns GitLab sign-in off |
| `GITLAB_CLIENT_SECRET` | Its secret. In production a file secret: `/run/secrets/docswatcher.gitlab.client-secret` | blank, which turns GitLab sign-in off |
| `DOCSWATCHER_GITLAB_TOKEN_KEY` | Base64 of 32 random bytes (`openssl rand -base64 32`), the AES-256 key connected groups' access tokens are encrypted under. In production a file secret: `/run/secrets/docswatcher.gitlab.token-key` | blank, which turns GitLab connections off |
| `DOCSWATCHER_API_TOKEN` | Bearer token for `/api/**`: the owner's access | blank |
| `DOCSWATCHER_WEB_ORIGIN` | The site's public origin. Used for CORS, as the only `Origin` a signed-in member's POST may carry, and as the base of the sign-in callback and redirect. Production: `https://docswatcher.vukisha.co.ke` | `http://localhost:3000` |
| `DOCSWATCHER_NOTIFY_URL`, `DOCSWATCHER_NOTIFY_TOKEN` | Where early-access requests are emailed from (`notify/`), and its token. Unset: stored only | blank |
| `BREVO_API_KEY` | Brevo API key for alert email. In production it is a file secret instead: `/run/secrets/docswatcher.brevo.api-key`, which takes precedence. Blank: the app says email alerts are off and sends Slack alerts only | blank |
| `DOCSWATCHER_ALERTS_FROM` | From address of alert email; its domain must be authenticated in Brevo | `alerts@vukisha.co.ke` |
| `DOCSWATCHER_KNOWLEDGE_DIR` | A local knowledge checkout instead of the bundled release | bundled |
| `PORT` | HTTP port | `8080` |

Worker tuning lives under `docswatcher.worker` in `application.yaml`: `threads`, `poll-ms`, `enabled`.

Alerts before the date (`docs/adr/0010-alerts-before-the-date.md`, package `alerts`) run once a day at `docswatcher.alerts.cron` (UTC, default 06:00; `-` turns it off). The owner can run them now with `POST /api/alerts/run`; a rerun sends nothing already sent. In Brevo, authenticate the sender's domain and turn off open and click tracking so links arrive as written. Public routes `/subscribe` and `/unsubscribe` must reach the app, like `/early-access`.

Whole-scan limits live under `docswatcher.scan`: `max-files` (20000), `max-total-mb` (200) and `max-seconds` (600). Each scan holds the files it read in memory until it ends. A scan that reaches a limit is recorded as incomplete (`stats.incomplete` on the scan run), closes no finding or issue, and posts a check run titled `Incomplete scan: ...` that is never `success`. See "Scan limits" in `docs/10-reference.md`.

Each scan runs in a process of its own (`docswatcher.scan.isolation: process`, ADR 0011): a JVM started from this app's own jar or classpath, with a 1 GB heap cap (`process.heap-mb`), killed past `max-seconds` plus a minute, and given none of the app's environment. A parser crash, a hang or a scan out of memory fails that run with a message in its `error`; webhooks, the API and sign-in carry on. It costs a JVM start per scan, about 0.6 s on an idle machine and up to 1.5 s on a busy one. Size the container for the server plus `threads` scan processes. `in-process` scans in the server's JVM instead, sized for `threads` times `max-total-mb` of heap. At startup the app starts one scan process to check it can; if not, it logs an error and scans in-process. Runs left `running` by a restart or a crash are failed and queued again once, at startup and every five minutes (`reclaim-after-seconds`). See `docs/10-reference.md`.

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

### Sign-in with GitHub

The organisation dashboard signs people in through the same App's OAuth client. No separate OAuth
App is needed. See `docs/adr/0008-sign-in-with-github.md` for the design.

1. In the App's settings (Settings, Developer settings, GitHub Apps, DocsWatcher, General),
   under **Identifying and authorizing users**, add the callback URL
   `https://docswatcher.vukisha.co.ke/auth/github/callback`. It must equal
   `{DOCSWATCHER_WEB_ORIGIN}/auth/github/callback` exactly. Leave "Request user authorization
   (OAuth) during installation" off. Keep "Expire user authorization tokens" on: the app uses the
   token only while signing someone in.
2. Copy the **Client ID** from the top of that page into `GITHUB_CLIENT_ID`. It is not a secret.
3. Under **Client secrets**, press **Generate a new client secret** and copy it at once, because
   GitHub shows it only once. Store it as the Docker secret `docswatcher.github.client-secret`,
   mounted at `/run/secrets/docswatcher.github.client-secret`. For local runs,
   `GITHUB_CLIENT_SECRET` works too.
4. Set `DOCSWATCHER_WEB_ORIGIN` to the site's public origin, and route `/auth/` and `/api/` on
   that host to the app (nginx, beside `/webhooks/` and `/early-access`).
5. Restart the app. `GET /auth/me` then answers `"enabled": true`, and `/app` offers "Sign in with GitHub".

No extra permissions are needed. GitHub works out which installations and repositories a person
can reach from the App's existing grant and the person's own access. To rotate the secret,
generate a new one, deploy it, then delete the old one on the same page.

Findings arrive as issues labelled `docswatcher` and `docswatcher:<severity>`. Adding these labels to a finding issue acts on it:

| Label | Effect |
|---|---|
| `docswatcher:fix` | Dispatches the fix workflow with the finding's context |
| `docswatcher:snooze-30d` | Snoozes for thirty days |
| `docswatcher:not-in-prod` | Marks the finding informational |
| `docswatcher:not-affected` | Marks the finding `not_affected`: the change does not touch this code. It leaves the open counts, no longer fails the check run, and survives rescans |

Closing a finding's issue by hand is the same verdict as `docswatcher:not-affected`, and reopening it makes the finding open again. The App closes issues itself when a finding's evidence disappears; those closes come from its bot account and are ignored. Labels, closes and reopens all require the sender to have write permission or above on the repository; anything less, or a permission the App cannot look up, is ignored.

## GitLab setup

GitLab has no App to install. A person signs in through a GitLab OAuth application, and each group
is connected with an access token its maintainer creates for DocsWatcher. See
`docs/adr/0011-gitlab.md` for the design, and "Connecting GitLab" in `docs/07-getting-started.md`
for the owner's steps.

1. **Token key.** `openssl rand -base64 32`, stored as the Docker secret
   `docswatcher.gitlab.token-key`. Keep a copy somewhere safe: without it, stored tokens cannot be
   read, and every group has to be connected again.
2. **OAuth application** (for sign-in), on the instance in `GITLAB_BASE_URL`: callback
   `{DOCSWATCHER_WEB_ORIGIN}/auth/gitlab/callback`, confidential, scope `read_api` only. Its id goes
   in `GITLAB_CLIENT_ID`, its secret in the Docker secret `docswatcher.gitlab.client-secret`.
3. **Routes.** nginx routes `/auth/` (which already covers `/auth/gitlab/`) and `/webhooks/gitlab`
   on the site's host to the app.
4. **Connecting a group.** A maintainer signs in with GitLab and fills in "Connect a GitLab group"
   on `/app`, or the owner calls `POST /api/gitlab/connections` with the bearer token. They then
   add the webhook it gives them (Push events and Issues events).

Findings arrive as issues labelled `docswatcher` and `docswatcher:<severity>`. The snooze,
not-in-prod and not-affected labels, closing and reopening work as on GitHub, from Developer and
above. Each scan sets a `DocsWatcher` commit status: `failed` for a breaking finding in a
production project, otherwise `success`, with what is open in the description. There is no fix
pull request on GitLab.

## Fix handoff

Customers add `src/main/resources/templates/docswatcher-fix.yml` to their repository as `.github/workflows/docswatcher-fix.yml` and set the `ANTHROPIC_API_KEY` secret. The app serves the file at `GET /api/setup/workflow`. Pressing Fix, or adding the fix label, sends a `repository_dispatch` event of type `docswatcher-fix` whose `client_payload` holds the finding with its evidence locations (path, line, column; no source text), the migration block, and the guide URL. Claude Code runs in the customer's Actions with the customer's key. DocsWatcher never spends tokens on a fix.

The agent reads code DocsWatcher does not control, so its limits are enforced by the workflow, not the prompt:

- It may edit only the files the finding names, which must be tracked regular files outside `.github/`, and may read only the checkout and the finding. It cannot run commands or reach the web.
- Its job has `contents: read`. The workflow then checks that only the named files changed, with no file created, deleted or re-moded.
- A second job, with no agent and no key, applies the change, checks it again, and opens the pull request.

`FixWorkflowTemplateTest` keeps these properties from regressing.

## API

All under `/api`, JSON. The owner authenticates with the bearer token. A signed-in member uses the session cookie and can use every route below except `/early-access`, and cannot use the OTLP ingest. A repository's ingest token opens the OTLP ingest and nothing else, and pins every span to that repository. The member sees only their organisations and repositories, and needs write access for the POSTs (ADR 0008).

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
| `POST /findings/{repoId}/{contractId}/{changeId}/not-affected` | |
| `POST /findings/{repoId}/{contractId}/{changeId}/fix` | dispatches the fix workflow, returns the payload |
| `POST /repos/{id}/findings/snooze`, `/not-in-prod`, `/fix` | the same three actions, with the finding in the body: `{"contract": "...", "change": "...", "days": 30}`. For contract ids with a slash |
| `GET /repos/{id}/runtime` | runtime observations for the repository |
| `GET /repos/{id}/runtime/summary` | deprecated calls production made, findings never observed, when telemetry last arrived |
| `GET /repos/{id}/runtime/tokens` | the repository's live ingest tokens, without the tokens |
| `POST /repos/{id}/runtime/tokens` | body `{"label": "..."}`; creates an ingest token and returns its secret once (write) |
| `POST /repos/{id}/runtime/tokens/{tokenId}/revoke` | revokes one (write) |
| `POST /repos/{id}/rescan` | queues a manual scan |
| `GET /setup/workflow` | the fix workflow YAML |
| `GET /early-access` | early-access requests, newest first |

Outside `/api`, `POST /early-access` takes the Teams page form without a token: validated,
length-limited, a honeypot, 5 requests per client per hour, one row per email. See
`docs/adr/0005-early-access-requests.md`.

GitLab connections: `GET /gitlab/connections`, `POST /gitlab/connections` with `{"namespace": "...", "token": "..."}`, and `POST /gitlab/connections/{id}/disconnect`. Signed-in members may use them; the token is the authority to connect, and GitLab's Maintainer role to disconnect.

Sign-in, outside `/api`: `GET /auth/github/login`, `GET /auth/github/callback`, `GET /auth/gitlab/login`, `GET /auth/gitlab/callback`, `GET /auth/me`, and `POST /auth/logout`. GitLab webhooks: `POST /webhooks/gitlab`.

Health: `GET /actuator/health`, no token.

## Tests

```
../mvnw -pl app verify
```

Needs Docker for the Postgres Testcontainer. The suite covers webhook signatures, webhook replay from recorded payloads, the scan worker end to end against a local git repository, the dashboard API, and the Flyway schema. The engine is replaced by a deterministic fake behind the `ScanEngine` interface, so these tests do not depend on the engine's detectors. `ProcessScanEngineIT` starts real scan processes: it scans every fixture in a process and in the JVM and compares the results, and checks that a crash, a hang, a heap overrun and a flood of output each become a failed scan with a clear message. `ScanIsolationIT` runs a crashing scan through the worker and reclaims abandoned runs.
Needs Docker for the Postgres Testcontainer. The suite covers webhook signatures, webhook replay from recorded payloads, the scan worker end to end against a local git repository, the dashboard API, and the Flyway schema. GitLab is a MockRestServiceServer behind the real `GitLabApi`: its sign-in, connections, webhook tokens, and a scan with its issues and commit status. The engine is replaced by a deterministic fake behind the `ScanEngine` interface, so these tests do not depend on the engine's detectors.

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
