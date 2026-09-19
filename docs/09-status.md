# Status: what is built, what is pending

Last updated 2026-09-18. Everything below was verified by running it on that date, not by reading the code.

## One-line status

The scanner works end to end. You can point it at a real repository and get correct findings with exact file and line locations, from a browser, a command line, or a GitHub App. What is missing is deployment, source automation, and breadth of provider coverage.

## Built and verified

| Component | State | Evidence |
|---|---|---|
| Knowledge base | 10 providers, 77 change records, 33 fixtures | `knowledge/scripts/validate` reports 0 errors, 0 warnings |
| Java engine | 3 detection layers, matcher, validator | 130 tests pass |
| TypeScript engine | Same three layers, runs in the browser | 28 unit tests pass |
| Two-engine parity | Byte-identical output on every fixture | 14 of 14 pass |
| Command line | `scan`, `match`, `validate`, JSON and text | 4 tests pass, native binary builds |
| Server app | Webhooks, scan worker, REST API, fix dispatch | 31 tests pass, including Postgres and a real clone |
| Web site | Scanner, calendar, dashboard, finding detail | 8 browser tests pass |
| Relay worker | Tarball streaming with permissive origins | 9 tests pass |
| Documentation | 11 documents and 3 decision records | This set |
| Deployment | Live behind a Cloudflare Tunnel, five containers, no inbound ports | Deployment and operations runbooks, in the private ops repository |

Total: 165 Java tests, 28 TypeScript unit tests, 8 browser tests, 9 relay tests.

### Verified against real repositories

| Repository | Files | Findings |
|---|---|---|
| `anthropics/anthropic-quickstarts` | 467 | 10 |
| `openai/openai-quickstart-python` | 13 | 3 |
| `Shopify/shopify-app-template-node` | 30 | 1 |
| `stripe-samples/accept-a-payment` | 650 | 0 |

The two zero-finding repositories are as important as the others. They are evidence the scanner does not fire indiscriminately.

## Pending

Ordered by what most limits the product today.

### 1. The fix loop has never run against a real repository

This is the largest gap between the product as built and the product as
pitched, and the only one blocked on something outside the code.

The dispatch path is built and tested against a fake GitHub client. The
workflow template exists and is served by the app. What has never happened is
a real run: a real label on a real issue, dispatching a real workflow, opening
a real pull request.

It needs a GitHub App registered under the owner's account, giving an app id, a
private key and a webhook secret, plus a throwaway repository holding an API
key in its secrets. None of that can be created from here.

### 2. Record counts are uneven, and that is the providers' fault

All ten launch providers are covered. The counts differ a lot, from 32 for
OpenAI to 2 for Twilio, Slack, SendGrid and GitHub, and 1 for Stripe. That
reflects what each provider actually publishes. Google keeps a table with an
announced date and a shutdown date per model. Twilio's changelog carries almost
no dated API deprecations, and Stripe publishes almost no removal dates at all.

Padding a thin provider would mean inventing dates, so the counts stay honest.
The gap worth closing is not more records for these ten, it is more providers.

### 3. Change records are hand-written

Every one of the 50 records was researched and written by hand today from the provider's own deprecation page. Nothing watches those pages. The ingestion design in the vision document, where a scheduled job diffs sources and opens a pull request against the knowledge base for a human to approve, is not built.

This is the single biggest gap between the product as built and the product as pitched. Without it the knowledge base goes stale.

### 4. The fix loop has never run against a real repository

The dispatch path is built and tested with a fake GitHub client. The workflow template exists and is served from the app. What has not happened is a real end-to-end run: a real label on a real issue, dispatching a real workflow, opening a real pull request. The closed-loop test described in the test plan is not written.

### 5. The runtime layer does not exist

Observing `Deprecation` and `Sunset` response headers from live traffic was deliberately deferred to v2, as an OpenTelemetry processor. The mockup's "1,204 calls/day" line is not backed by anything yet.

### 6. The benchmark corpus is informal

The test plan calls for fifty labeled public repositories with measured precision and recall against targets of 95 and 80 percent. Four repositories were scanned today and their results eyeballed. There is no labeled ground truth and no scoring script, so the precision claim is currently an argument, not a measurement.

### 7. Smaller gaps

| Gap | Detail |
|---|---|
| Native app image | Still not building. Two blockers cleared, one remains. Diagnosis in `app/README.md` |
| Dashboard is single-repo | The map and horizon render one stored scan. Blast radius across an organisation exists in the API but has no page |
| Snooze is local only | In the web app it lives in browser storage. The server app stores it properly |
| No authentication on the web app | The dashboard reads a local scan. There is no sign-in |
| API auth is one shared token | Fine for a pilot, not for customers |
| Incremental scanning | Push events rescan the whole repository. Fast enough today |
| GitLab and Bitbucket | Not started, deliberately |
| Ruby, PHP, C# call sites | Manifests and literals cover them; there are no tree-sitter queries |

## Known issues

**The tree-sitter binding prints a warning.** The Java binding loads a native library, so the JDK prints a restricted-method warning on every run. Harmless. Passing `--enable-native-access=ALL-UNNAMED` silences it.

**The native app image does not build.** The CLI's does. The remaining failure is inside `native-image` itself and is not yet diagnosed. The JVM jar works, so this only costs startup time and memory, which matters for free hosting tiers.

**The native CLI needs a zlib development link.** Documented in `cli/README.md`, with a workaround that needs no administrator rights.

**jsDelivr caches branch references.** A URL scan through that route can lag the newest commit by minutes. The relay and the GitHub API routes do not have this property.

**Sandboxed hosts block URL scans.** The published preview permits no outbound requests at all, which is why real repositories are vendored into the page. Covered in `docs/06-testing-guide.md`.

## What today changed about the plan

Three things were decided by evidence rather than design.

1. **A browser cannot read a GitHub tarball.** Checked with real requests. This produced the relay, recorded in `docs/adr/0003-browser-scanning-and-repo-fetch.md`.
2. **jsDelivr is a better default than the GitHub API.** It serves any public repository with no hourly limit, so it now leads the fetch chain.
3. **Detectors as data paid off immediately.** A false positive, where a Stripe version string matched a Shopify rule, was fixed by editing one line of YAML. Both engines picked up the fix with no code change.

## Next three things worth doing

1. **Run the fix loop once, for real.** It is the part of the pitch nobody has
   seen work, and it is blocked only on a GitHub App registration.
2. **Build the benchmark corpus.** Fifty labelled repositories and a scoring
   script. It turns the precision claim into a number, and every true finding
   is a real issue that could be opened on a real project.
3. **Automate ingestion.** A scheduled job that drafts change records as pull
   requests. Without it the knowledge base decays from the day it was written.
