# Getting started

A guide for someone who has just cloned this repository and knows nothing about it. Every command here was run on a Linux machine on 2026-09-18.

## What this is

DocsWatcher scans a repository for every external API it depends on: SDK packages, HTTP endpoints, AI model IDs in config, pinned API versions. It matches that inventory against a knowledge base of provider deprecations. It tells you which of your calls has an expiry date, at which file and line.

You can pin a package. You can't pin someone else's API — that gap is what this watches.

## Prerequisites

| Tool | Version | Needed for | Check |
|---|---|---|---|
| GraalVM JDK | 25 | Engine, CLI, server app | `java --version` |
| Node.js | 22 or later | Web site, relay | `node --version` |
| Docker | any recent | Server app tests, local Postgres | `docker ps` |
| git | any recent | Everything | `git --version` |

Node and git are usually already present. The JDK is the one you probably need.

### Installing the JDK

The repository expects GraalVM Community 25 in your home directory. Download and unpack it:

```
mkdir -p ~/.jdks && cd ~/.jdks
curl -sL -o graalvm.tar.gz \
  https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-25.0.2/graalvm-community-jdk-25.0.2_linux-x64_bin.tar.gz
tar xzf graalvm.tar.gz && rm graalvm.tar.gz
```

Then point the build at it. The repository ships `build-env.sh` for this:

```
cd /path/to/DocsWatcher
source build-env.sh
java --version        # should say GraalVM Community 25
```

If your unpacked directory has a different version suffix, edit `build-env.sh` to match.

## First build

```
source build-env.sh
./mvnw -q -pl knowledge,engine,cli -am install -DskipTests
```

Use `./mvnw`, never a globally installed `mvn`. The repository carries its own Maven settings so the build resolves from Maven Central. See troubleshooting below if you are behind a corporate mirror.

This takes a few minutes the first time. It produces `cli/target/docswatcher-cli.jar`.

## Five minutes: see it work

Scan one of the bundled fixtures:

```
java -jar cli/target/docswatcher-cli.jar match \
  knowledge/fixtures/stripe-java-sources/repo \
  --knowledge knowledge --format text
```

You get a drift report naming the deprecated Stripe Sources API, the file, and the line:

```
⚠ External API drift · repo
Scanned 2 files · 4 external contracts found · 2 need attention

Warning · behavior changed, no date

1. Stripe · endpoint `POST /v1/sources`
   Sources API deprecated, removal planned
   Referenced in: src/main/java/pay/SourceClient.java:14
   Provider guidance: migrate to POST /v1/payment_methods (large effort)
```

Now try a real project:

```
git clone --depth 1 https://github.com/openai/openai-quickstart-python /tmp/oqp
java -jar cli/target/docswatcher-cli.jar match /tmp/oqp --knowledge knowledge --format text
```

Three breaking findings, including the Assistants API sunset at three call sites found through the syntax tree.

The command exits 1 when any breaking finding is open, which is what makes it useful as a CI step.

## Running the web site

```
cd web
npm install
npm run dev          # http://localhost:3000
```

The landing page has three input modes:

| Mode | What it does | Network |
|---|---|---|
| Sample repository | Scans a bundled copy of a real public repo, or a fixture | None |
| GitHub or GitLab URL | Fetches any public GitHub repo or GitLab project and scans it | jsDelivr or the GitHub API; the GitLab API |
| Local folder | Reads a directory you pick | None |

Every mode runs the same engine in your browser. No code is uploaded.

Two other pages: `/calendar` lists every deprecation we track by month, and `/app` is the dashboard with the provider map and the twelve-month horizon.

## Running the server app

The server app is the backend for the GitHub App and for connected GitLab groups. It needs Postgres.

```
cd app
docker compose up -d
curl -s localhost:8080/actuator/health
```

Without GitHub credentials it starts and serves the API but has nothing to scan. To connect it to a real GitHub App, follow the setup steps in `app/README.md`.

## Connecting GitLab

GitLab teams get the same service as the GitHub App: a scan on every push to the default branch, an
issue per finding, a `DocsWatcher` commit status, and the organisation dashboard. The design is in
`docs/adr/0011-gitlab.md`. As the deployment's owner, you do steps 1 to 4 once. Each team then
does step 5 for its group.

1. **Generate the token key.** Run `openssl rand -base64 32` and store the output as the Docker
   secret `docswatcher.gitlab.token-key`, mounted at `/run/secrets/docswatcher.gitlab.token-key`.
   It encrypts the access tokens groups hand to DocsWatcher. Keep a copy somewhere safe. If it is
   lost, every group has to be connected again. For local runs, `DOCSWATCHER_GITLAB_TOKEN_KEY` works
   too.
2. **Create the OAuth application** for sign-in. On gitlab.com: your avatar, Edit profile,
   Applications, Add new application. For a self-managed instance, an admin can create it as an
   instance-wide application instead (Admin area, Applications).
   - Name: `DocsWatcher`.
   - Redirect URI: `https://docswatcher.vukisha.co.ke/auth/gitlab/callback`. It must equal
     `{DOCSWATCHER_WEB_ORIGIN}/auth/gitlab/callback` exactly.
   - Confidential: on.
   - Scopes: `read_api` only. Sign-in only asks who the person is and which projects they belong
     to. It never writes with their token, and the token is dropped once they are signed in.
3. **Configure the app.** Put the application's id in `GITLAB_CLIENT_ID`. Store its secret as the
   Docker secret `docswatcher.gitlab.client-secret`; GitLab shows it only once. For a self-managed
   instance, set `GITLAB_BASE_URL` to its web address, for example `https://gitlab.example.com`.
   The default is `https://gitlab.com`.
4. **Route the paths.** nginx must send `/auth/` (which already covers `/auth/gitlab/`) and
   `/webhooks/gitlab` on the site's host to the app, beside `/webhooks/github`. Restart the app.
   `GET /auth/me` then answers `"providers": {"gitlab": true}`, and `/app` offers "Sign in with
   GitLab".
5. **Connect a group** (each team's maintainer):
   1. In the group, open Settings, Access tokens, and add a token named `DocsWatcher` with the
      **Maintainer** role and the **`api`** scope. Choose an expiry and note it: DocsWatcher shows
      it, and a scan fails after it until a new token is entered. Group access tokens need a paid
      tier on gitlab.com. On Free, create a project access token in each project, and connect each
      project by its full path.
   2. Sign in with GitLab on `/app`. Under **Connect a GitLab group**, enter the group's path (for
      example `acme` or `acme/platform`) and the token. DocsWatcher checks the token's scope and
      role with GitLab, stores it encrypted, and queues a first scan of every project.
   3. Add the webhook it shows, in the group's Settings, Webhooks (a paid tier), or in each
      project's Settings, Webhooks. Use the URL `https://docswatcher.vukisha.co.ke/webhooks/gitlab`
      and the **Secret token** DocsWatcher showed, with **Push events** and **Issues events**
      ticked. The secret token is shown once. To get a new one, disconnect and connect again.
   4. Sign in again to see the group on the dashboard.

   To replace an expiring token, connect the same path again with the new token. The webhook
   stays as it is.

## Running the tests

```
source build-env.sh && ./mvnw verify        # 165 Java tests
cd web && npm test                           # 28 TypeScript unit tests
cd web && npm run e2e                        # 8 browser tests
cd relay && npm test                         # 9 relay tests
./knowledge/scripts/validate                 # knowledge base invariants
./knowledge/scripts/parity                   # both engines must agree exactly
```

The parity check is the one to run if you only run one. It scans all 34 fixtures with the Java engine and the browser engine and compares the output byte for byte.

Tests that touch the network are off by default:

```
cd web && DOCSWATCHER_LIVE=1 npx playwright test
```

## Troubleshooting

**`./mvnw` fails resolving dependencies, or returns 403.** Your global `~/.m2/settings.xml` probably points at a corporate mirror. The repository ships `.mvn/settings.xml` pointing at Maven Central and `.mvn/maven.config` that selects it. Always use `./mvnw`. If you previously ran `mvn wrapper:wrapper`, check that `.mvn/wrapper/maven-wrapper.properties` still points at `repo.maven.apache.org`.

**`java --version` says 21 or something else.** You did not run `source build-env.sh`, or its path does not match your unpacked JDK directory. Run `ls ~/.jdks` and edit the script.

**The CLI prints a warning about restricted methods.** The tree-sitter binding loads a native library. Harmless. Add `--enable-native-access=ALL-UNNAMED` before `-jar` to silence it.

**Native CLI build fails at the link step with a missing `-lz`.** The linker needs zlib development headers, from `zlib1g-dev` on Debian and Ubuntu. Without administrator rights, create the link the build profile looks for:

```
mkdir -p cli/native-link && ln -sf /usr/lib/x86_64-linux-gnu/libz.so.1 cli/native-link/libz.so
```

**App tests fail with a Docker error.** Testcontainers needs a running Docker daemon your user can reach. Check `docker ps`.

**`npm run e2e` says no browser.** Install the one Playwright needs:

```
cd web && npx playwright install chromium
```

**GitHub URL mode says every route was blocked.** Some hosts forbid outbound requests entirely, including the published artifact preview. Use a sample or a local folder there, or run the site locally where URL mode works.

**A scan by URL says the rate limit is reached.** On GitHub that is the API's 60 per hour per address. Paste a personal access token in the field on the page, or wait. The jsDelivr route has no such limit and is tried first. On gitlab.com it is 500 API requests a minute per address: wait a minute, or paste a GitLab token.

**A self-managed GitLab URL says the page's Content-Security-Policy does not allow it.** The deployed site allows gitlab.com only. Run the site yourself (`cd web && npm run dev`) to scan your own instance. See `docs/08-features.md`.

## Where to go next

| Document | Read it for |
|---|---|
| `docs/00-vision.md` | Core architecture vision, motivation, and principles |
| `docs/08-features.md` | Every feature, what it does, how it works |
| `docs/10-reference.md` | CLI flags, REST endpoints, environment variables |
| `docs/02-schemas.md` | The data contracts between every component |
| `docs/03-knowledge-base-guide.md` | How to add a provider or a deprecation |
| `docs/05-deployment.md` | Deployment architecture and container topology |
| `docs/06-testing-guide.md` | The four ways to test, and verified test repositories |
| `docs/09-status.md` | Architecture status, test coverage, and roadmap |
| `docs/01-architecture.md` | How the pieces fit together |
| `docs/adr/` | Why the big decisions went the way they did |
