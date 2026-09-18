# How to test DocWatcher

Four surfaces, four ways to try it. The UI is one of them, not the only one. Every command below was run on this machine on 2026-09-18.

| Surface | You need | Good for |
|---|---|---|
| Web UI | A browser | Seeing it work in thirty seconds |
| Command line | The jar or the native binary | Real repos, CI, scripting |
| REST API | The app plus Postgres | The dashboard and org-wide views |
| GitHub App | A GitHub App registration | The full loop, issues and fix PRs |

## 1. The web UI

Published at the artifact link from the session. Three input modes on the landing page:

- **Sample.** Pick one of the fourteen bundled fixtures. No network.
- **Folder.** Drop a local directory. Nothing leaves the browser.
- **GitHub URL.** Paste any public repository URL. The page tries three routes in order and uses the first that the host permits:

| Route | Hosts | Limit |
|---|---|---|
| Relay | Your Cloudflare Worker, if configured | None |
| jsDelivr | `data.jsdelivr.com`, `cdn.jsdelivr.net` | None, but branch refs are cached so a scan can lag the newest commit |
| GitHub API | `api.github.com`, `raw.githubusercontent.com` | 60 requests per hour per address, two per scan |

jsDelivr is tried before the GitHub API because it has no per-hour ceiling. The whole scan, including the tree-sitter call-site layer, runs in your browser either way.

### GitHub URL mode needs a host that permits outbound requests

Some embedding hosts block requests to other origins. The page tries every route before giving up, and if all are blocked it names each one and falls back to the sample tab rather than showing a bare "Failed to fetch". Samples and folders are unaffected, and they exercise exactly the same engine.

URL mode is verified working when the site is served from a normal host. Run it yourself:

```
cd web && npm run dev                      # http://localhost:3000
```

or serve the built export:

```
cd web && npm run generate
npx serve .output/public                   # or any static server
```

The same applies to any static host you deploy to, such as Cloudflare Pages.

The anonymous GitHub API limit is 60 requests an hour per address, and each scan spends two, so roughly thirty scans an hour. Paste a personal access token in the field to raise it. The token is held in memory and never stored.

Run it locally instead:

```
cd web && npm install && npm run dev      # http://localhost:3000
```

## 2. The command line

```
source build-env.sh                        # GraalVM 25
./mvnw -q -pl knowledge,engine,cli -am install -DskipTests
```

Then either the jar or the native binary:

```
java -jar cli/target/docwatcher-cli.jar match /path/to/repo --knowledge knowledge
cli/target/docwatcher match /path/to/repo --knowledge knowledge
```

Commands and flags:

| Command | Does | Exit code |
|---|---|---|
| `scan <path>` | Prints the inventory document | 0 |
| `match <path>` | Prints findings | 1 if any breaking finding is open |
| `validate [dir]` | Checks the knowledge base | 1 on any violation |

Useful flags: `--format json|text`, `--today YYYY-MM-DD` to pin the clock, `--include-low` to show low-confidence contracts, `--write-expected` to regenerate a fixture's expected files.

The exit code is what makes this useful in CI. Add it as a step and the build fails when someone introduces a call to a sunset endpoint.

## 3. The REST API

```
cd app && docker compose up -d             # Postgres plus the app
curl -s localhost:8080/actuator/health
curl -s -H "Authorization: Bearer $DOCWATCHER_API_TOKEN" \
  localhost:8080/api/orgs/acme/overview | jq
```

Endpoints cover the org overview, per-repo inventory and findings, the horizon, the provider map, blast radius for one deprecation, snooze and not-in-prod, and a manual rescan trigger. Full list in `app/README.md`.

## 4. The GitHub App

Register an app with contents read, checks write, issues write, metadata read, subscribed to installation, push, and issues. Point the webhook at `/webhooks/github` with a shared secret. Setup steps are in `app/README.md`.

On install it scans every repository and opens one issue per finding. On push it rescans and closes findings whose evidence is gone. Adding the `docwatcher:fix` label to an issue dispatches a workflow in the customer's own repository, which runs a coding agent on their own API key. Nothing is billed to us.

Fetch the workflow file the customer installs:

```
curl -s localhost:8080/api/setup/workflow
```

## Test repositories

Public repositories verified to produce findings, most interesting first.

| Repository | Files | Findings | What it exercises |
|---|---|---|---|
| `anthropics/anthropic-quickstarts` | 467 | 10 | Retired Claude model IDs across Python, TypeScript, and Markdown |
| `openai/openai-quickstart-python` | 13 | 3 | Assistants API sunset through a call site, plus a retired model in config |
| `openai/openai-cookbook` | very large | many | Stress test, thousands of model references |
| `Shopify/shopify-app-template-node` | small | 0 | A healthy repo on a supported API version |
| `stripe-samples/accept-a-payment` | medium | 0 | A healthy repo using PaymentIntents correctly |

The last two matter as much as the first three. A tool that finds something in every repository is not trustworthy.

Try the first two through the web UI by pasting their URLs. On the command line:

```
git clone --depth 1 https://github.com/openai/openai-quickstart-python /tmp/oqp
java -jar cli/target/docwatcher-cli.jar match /tmp/oqp --knowledge knowledge --format text
```

That prints three breaking findings, including the Assistants API sunset at three exact call sites.

## The test suite

```
source build-env.sh && ./mvnw verify       # 165 Java tests
cd web && npm test                          # TypeScript engine unit tests
cd web && npm run e2e                       # Playwright, offline
cd relay && npm test                        # 9 worker tests
./knowledge/scripts/parity                  # both engines must agree byte for byte
./knowledge/scripts/validate                # knowledge base invariants
```

Live network tests are off by default so the suite cannot be broken by someone else's repository changing:

```
cd web && DOCWATCHER_LIVE=1 npx playwright test github-live
```

Two tests run there. One scans a real public repository through the browser and asserts the findings. The other blocks `api.github.com` and `raw.githubusercontent.com` outright, so only the jsDelivr route can succeed, which is what makes it a real test of that route instead of the fallback.

## What to look at first

Run the parity check. It scans all fourteen fixtures with the Java engine and the browser engine and compares the output byte for byte. If that passes, the scanner in your browser and the scanner in CI are provably the same scanner.
