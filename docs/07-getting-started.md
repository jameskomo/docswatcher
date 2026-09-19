# Getting started

A guide for someone who has just cloned this repository and knows nothing about it. Every command here was run on a Linux machine on 2026-09-18.

## What this is

DocWatcher scans a repository for every external API it depends on: SDK packages, HTTP endpoints, AI model IDs in config, pinned API versions. It matches that inventory against a knowledge base of provider deprecations. It tells you which of your calls has an expiry date, at which file and line.

Think of it as Dependabot for the APIs you call rather than the packages you install.

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
cd /path/to/DocWatcher
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

This takes a few minutes the first time. It produces `cli/target/docwatcher-cli.jar`.

## Five minutes: see it work

Scan one of the bundled fixtures:

```
java -jar cli/target/docwatcher-cli.jar match \
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
java -jar cli/target/docwatcher-cli.jar match /tmp/oqp --knowledge knowledge --format text
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
| GitHub URL | Fetches any public repo and scans it | jsDelivr, or the GitHub API |
| Local folder | Reads a directory you pick | None |

Every mode runs the same engine in your browser. No code is uploaded.

Two other pages: `/calendar` lists every deprecation we track by month, and `/app` is the dashboard with the provider map and the twelve-month horizon.

## Running the server app

The server app is the GitHub App backend. It needs Postgres.

```
cd app
docker compose up -d
curl -s localhost:8080/actuator/health
```

Without GitHub credentials it starts and serves the API but has nothing to scan. To connect it to a real GitHub App, follow the setup steps in `app/README.md`.

## Running the tests

```
source build-env.sh && ./mvnw verify        # 165 Java tests
cd web && npm test                           # 28 TypeScript unit tests
cd web && npm run e2e                        # 8 browser tests
cd relay && npm test                         # 9 relay tests
./knowledge/scripts/validate                 # knowledge base invariants
./knowledge/scripts/parity                   # both engines must agree exactly
```

The parity check is the one to run if you only run one. It scans all 14 fixtures with the Java engine and the browser engine and compares the output byte for byte.

Tests that touch the network are off by default:

```
cd web && DOCWATCHER_LIVE=1 npx playwright test
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

**A scan by URL says the rate limit is reached.** That is the GitHub API's 60 per hour per address. Paste a personal access token in the field on the page, or wait. The jsDelivr route has no such limit and is tried first.

## Where to go next

| Document | Read it for |
|---|---|
| `docs/00-vision.md` | Why this exists and who buys it |
| `docs/08-features.md` | Every feature, what it does, how it works |
| `docs/10-reference.md` | CLI flags, REST endpoints, environment variables |
| `docs/02-schemas.md` | The data contracts between every component |
| `docs/03-knowledge-base-guide.md` | How to add a provider or a deprecation |
| `docs/06-testing-guide.md` | The four ways to test, and verified test repositories |
| `docs/09-status.md` | What is built and what is still pending |
| `docs/01-architecture.md` | How the pieces fit together |
| `docs/adr/` | Why the big decisions went the way they did |
