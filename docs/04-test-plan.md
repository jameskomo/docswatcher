# Test plan

DocsWatcher is correct when it finds the right external contracts and nothing else, and when the browser scanner and the CI scanner agree. Every test below serves one of those two properties or protects the plumbing that delivers them.

Stack under test: Java 25, Maven multi-module, Spring Boot 4, GraalVM native-image, a TypeScript engine in the Nuxt frontend, and a knowledge base of YAML and fixtures. Two engines, one rule set, one fixture corpus.

## Test layers

| Layer | Tool | Runs | Protects |
|---|---|---|---|
| Engine unit | JUnit 5 | Every PR | Individual detector and loader behaviour |
| Fixture snapshot | JUnit 5 with committed expected files | Every PR | Detection output per fixture |
| Two-engine parity | Maven plus Vitest, shared runner | Every PR | Browser and CI agree byte for byte |
| Matcher properties | jqwik | Every PR | Matcher invariants under random input |
| Knowledge base validation | `knowledge/scripts/validate` | Every PR | Schema, dates, dialect, fixture coverage |
| App unit and webhook replay | JUnit 5, Spring Boot test, WireMock | Every PR | Webhook handling and job dispatch |
| API contract | OpenAPI validator plus generated client | Every PR | Frontend and backend agree on the API |
| Nuxt component | Vitest and Vue Test Utils | Every PR | Map, horizon, finding page rendering |
| Native-image | Fixture run against the native binary | Nightly | Native build behaves like the JVM |
| Performance budget | Timed scan of a vendored repo | Nightly | No regression in scan time |
| Benchmark corpus | Precision and recall runner | Nightly | Real-world detection quality |
| Visual regression | Playwright screenshots | Nightly | Dashboard views in light and dark |
| End-to-end | Playwright against a seeded stack | Nightly | Full user paths |
| Browser scanner parity | Playwright | Nightly | Public scan equals CLI scan |
| Closed-loop fix | Fixture repo plus Claude Code action | Weekly | Fix PRs work and clear the finding |
| Link check | `scripts/check-links` | Weekly | Source and guide URLs resolve |
| Corpus re-pin | Benchmark at newer commits | Weekly | Drift against live repos |

## Engine unit tests

Module `engine/`, no Spring dependency. Table-driven JUnit 5 tests where each row is an input and the expected contract.

Loader tests:

- Loads a provider directory and rejects a detector without an `id`.
- Rejects a literal pattern using lookbehind, a named group, or an inline flag.
- Rejects a call-site rule without `requires` or without a `@call` capture.
- Rejects a `maps_to` endpoint absent from `methods.yaml`.

Manifest detector tests, one row per ecosystem:

- `pom.xml` with `com.stripe:stripe-java` yields an `sdk_package` contract with the version from the dependency element.
- `package.json` with `stripe` under `devDependencies` only yields the contract with `confidence: medium`.
- `requirements.txt` with a pinned version and with a range both yield the contract, version populated only when pinned.
- `go.mod` with an indirect dependency yields `confidence: low`.

Literal detector tests:

- Base URL inside a string literal yields an `endpoint` contract with the captured path.
- Same URL inside a line comment yields `confidence: low`.
- Same URL inside a markdown file yields `confidence: low`.
- Same URL inside `src/test/` yields `confidence: low`.
- Model ID pattern does not match inside a longer identifier such as `gpt-4-turbo-preview-cache-key`, once the boundary rule is applied.
- `exclude` globs prevent reads of `node_modules`.

Call-site detector tests:

- Java `Charge.create(params)` with the Stripe manifest present yields `sdk_method` and the mapped `endpoint`, same evidence on both.
- Same file without any Stripe manifest yields nothing from the call-site layer.
- Python attribute chain matches only when the module identifier is `stripe`.
- Evidence line and column point at the `@call` node, not at the enclosing statement.

Inventory serialisation tests:

- Contracts sorted by `id`, evidence sorted by `path` then `line`.
- Output is stable across two runs on the same input.

## Fixture snapshot tests

Each fixture in `knowledge/fixtures/` becomes one parameterised test. The engine scans `repo/` and the resulting `contracts` array is compared to `expected-inventory.json` as a canonical JSON string. The matcher then runs and the findings are compared to `expected-findings.json`.

- A failing snapshot prints a unified diff of the two JSON documents.
- `--write-expected` on the CLI regenerates the files. CI runs with it disabled and fails on any drift.
- Negative fixtures assert an empty findings array and either an empty contracts array or `low` confidence only.

## Two-engine parity test

One runner, `knowledge/scripts/parity`, executes every fixture through both engines and diffs the canonical JSON byte for byte.

- Java engine runs through the CLI on the JVM.
- TypeScript engine runs through a Node entry point in `web/` that loads the same detector files and the same pinned tree-sitter grammars.
- Any difference fails the PR. There is no tolerance and no allowlist.
- The test also asserts both engines loaded the same `knowledgeVersion` and the same grammar hashes from `engine/grammars.lock`.

## Matcher property tests

The matcher is a pure function from inventory plus active changes to findings. jqwik generates random inventories and change sets.

Invariants:

- Every finding references a contract present in the inventory and a change with `status: active`.
- No finding references a `draft`, `withdrawn`, or `expired` change.
- A contract with `confidence: low` produces no finding unless the run opts in.
- A snoozed finding does not reappear while `snoozedUntil` is in the future, and does reappear the day after.
- A snooze on change A never hides a finding for change B on the same contract.
- Output is sorted by `effective` ascending with nulls last, then severity, regardless of input order.
- Running the matcher twice on the same input yields identical output.
- `daysRemaining` equals the day difference between the run date and `effective`, negative when past.

## Performance budget

A vendored copy of a real repository of roughly four hundred files is committed under `engine/src/test/resources/perf/`. The nightly job scans it ten times on the native binary and records the median.

| Measure | Budget |
|---|---|
| Median scan time, manifest and literal layers | Under 500 ms |
| Median scan time, all layers | Under 3 s |
| Peak resident memory, native binary | Under 150 MB |
| Regression that fails the job | More than 20 percent over the last green run |

## Benchmark corpus

The benchmark measures real-world detection quality. It is a set of public repositories pinned to commit SHAs with hand-labelled ground truth.

| Metric | Target at launch | Fails nightly if |
|---|---|---|
| Precision | 95 percent or higher | Below 93 percent |
| Recall | 80 percent or higher | Below 75 percent |

Precision is defended harder than recall. A false positive costs a developer an afternoon and an uninstall. A missed contract is invisible until it matters.

Corpus rules:

- Starts at fifty repositories. At least ten are deliberate negatives that mention a provider without calling it.
- Every provider in the launch list has at least three positive repositories.
- Repositories are re-pinned weekly to their newest commit and the labels are re-reviewed when the diff touches a labelled file.

Ground truth format, `benchmark/corpus/<owner>-<repo>.yaml`:

```yaml
repo: acme/checkout-service
sha: 3f9c2a1e4b7d
labelled_by: reviewer-handle
labelled_on: 2026-09-18
contracts:
  - id: "stripe:endpoint:POST /v1/charges"
    locations:
      - src/payments/ChargeClient.java:88
      - src/payments/ChargeClient.java:142
  - id: "openai:model:example-model-1"
    locations:
      - config/application-prod.yml:31
not_contracts:
  - id: "twilio:sdk_package:twilio"
    reason: "Mocked in tests only, never imported in main."
```

Scoring:

- A true positive is a contract `id` present in both the inventory and the labels. Location agreement is reported separately and is not part of the headline number.
- A false positive is an inventory contract with `confidence` of `medium` or higher that is absent from the labels or present in `not_contracts`.
- A false negative is a labelled contract absent from the inventory.
- Results are broken down per provider and per detector `id`. The nightly report lists the ten worst detectors by false positives.

## Knowledge base validation tests

`knowledge/scripts/validate` runs on every PR to the knowledge base.

- Every YAML file validates against its JSON Schema.
- `effective` is after `announced` when both are set.
- An `active` change with a past `effective` date fails. It must move to `expired`.
- Every literal pattern compiles in both a Java and a JavaScript regex engine and uses only the shared dialect.
- Every call-site query parses against the pinned grammar for its language.
- Every `maps_to` target exists in the provider's `methods.yaml`.
- Every `active` change is referenced by at least one `expected-findings.json`.
- Every provider has at least one negative fixture.
- `methods.yaml` for a provider with a pinned spec matches a fresh generation from that spec.
- Detector `id` values are unique across the whole knowledge base and none were removed without a deprecation note.

Weekly, not per PR:

- Every `url` in `sources` and `migration` returns a success status.

## App tests

Module `app/`, Spring Boot 4.

### Webhook replay

Recorded GitHub payloads live in `app/src/test/resources/webhooks/`. Each test posts a payload with a valid signature to the webhook endpoint and asserts on side effects, with WireMock standing in for the GitHub API.

- `installation.created` for two repositories enqueues two scan jobs and stores the installation.
- `push` to the default branch enqueues an incremental scan for the changed paths only.
- `push` to a non-default branch enqueues nothing.
- `installation.deleted` removes the installation and cancels pending jobs.
- A payload with an invalid signature returns 401 and enqueues nothing.
- A replayed delivery ID is acknowledged and ignored.
- `issues.labeled` with the fix label dispatches a workflow run and records the dispatch on the finding.

### Scan worker

- A queued job clones at the recorded SHA, runs the engine, stores the inventory, runs the matcher, and posts one check run.
- A job for a repository over the size limit stores a skipped result with a reason and posts a neutral check run.
- A clone failure retries three times with backoff, then marks the job failed.
- Snooze and not-in-prod label commands on a finding issue update the finding status and survive the next rescan.

### API contract tests

The app publishes an OpenAPI document. The Nuxt frontend uses a client generated from it.

- The served OpenAPI document validates and matches the committed copy.
- Every endpoint in the document has at least one request and response example, and each example validates against its schema.
- The generated TypeScript client compiles against the committed document, so a backend change that breaks the frontend fails the backend PR.

### Nuxt component tests

Vitest and Vue Test Utils, rendered from fixture inventories and findings.

- The map renders one node per provider, sized by evidence count and coloured by the worst finding severity.
- The horizon places each finding on its `effective` date and groups findings with no date in a separate lane.
- The finding page shows every evidence location as a link to the file and line on GitHub.
- The fix button is disabled when a `fixPr` already exists and shows the PR link instead.
- Snooze writes the chosen date and re-renders the finding as snoozed without a reload.

### Visual regression

Playwright screenshots on seeded fixture data, light and dark theme, desktop and phone width.

- Org overview, map, horizon, finding page, inventory browser, public calendar, public scanner result.
- A pixel difference above the threshold fails the nightly job and attaches both images.

### End-to-end

Playwright against a seeded local stack with WireMock for GitHub and a stubbed workflow dispatch.

- Visitor pastes a public repository URL and sees an inventory within ten seconds.
- User signs in, installs the app on a seeded organisation, and sees the inventory check run appear on the seeded repository.
- User opens a finding, clicks fix, and sees a PR link appear after the stubbed dispatch reports back.
- User snoozes a finding for thirty days and it disappears from the open list and appears in the snoozed list.

### Browser scanner parity

Playwright loads the public scanner, submits a pinned public repository URL, waits for the scan, and downloads the inventory JSON. The test compares it to the CLI output for the same SHA, stored as a fixture.

- Byte-for-byte equality on the `contracts` array.
- The relay is exercised for real against a small public repository, so this test also covers ADR 0003.

## Closed-loop fix test

Runs weekly on our own API key, never per commit, because it spends tokens.

1. A fixture repository on GitHub contains a deprecated call site and a passing test suite.
2. The test triggers the fix label through the app.
3. The Claude Code action opens a PR.
4. Assert the PR exists, CI on the PR is green, and a rescan of the PR branch no longer reports the finding.

Because the fix is generated by a model, the test is statistical. Each fixture migration runs five times and the job passes when at least four succeed. Failures attach the PR diff and the CI log.

Initial fixture migrations:

- Java, Stripe Charges to PaymentIntents.
- Python, retired model ID to its replacement in a config file.
- TypeScript, Shopify API version bump across a client and its tests.

## Production verification

- **Dogfood.** The app is installed on our own repositories from the first deploy. We call GitHub, Anthropic, and Stripe, so every finding path is exercised by us first.
- **Synthetic canary.** A repository that intentionally references a retired model is rescanned in production every six hours. An alert fires if the expected finding is absent or if the scan takes longer than the performance budget.
- **Our own API.** The app's OpenAPI document is registered in the knowledge base as a provider. When we deprecate an endpoint, DocsWatcher reports it to any user calling it, including ourselves.

## Native-image tests

The JVM profile runs on every PR. The native binary runs nightly and before any release.

- Every fixture snapshot test runs against the native CLI binary and must match the JVM output.
- The native app boots, serves the health endpoint, and processes one recorded webhook end to end under WireMock.
- Tree-sitter grammars load through the Foreign Function and Memory API inside the native image for every supported language.
- Startup time and resident memory are recorded and compared to the performance budget.
- A reflection or resource hint missing from the native configuration is a nightly failure, not a release-day surprise.

## Coverage targets

| Area | Target | Measured by |
|---|---|---|
| Engine detectors and loader | 90 percent line coverage | JaCoCo on the JVM run |
| Matcher | 100 percent branch coverage plus property suite green | JaCoCo and jqwik |
| App webhook and worker paths | Every event type and every job outcome covered | Test inventory review |
| App API | Every endpoint has a contract test | OpenAPI validator |
| Frontend components | Map, horizon, finding page, scanner result covered | Vitest report |
| Knowledge base | Every active change referenced by a fixture, every provider has a negative | `knowledge/scripts/validate` |
| Benchmark corpus | Precision 95 percent, recall 80 percent | Nightly runner |

The benchmark numbers are the ones on the dashboard. Line coverage elsewhere is a floor, not a goal.

## Security audit

Separate from the test suite: the tests ask whether the code does what it should, the audit asks
whether it can be made to do something else. An agent-driven audit runs on a weekly cadence from the
maintainers' private operations repository, and findings are handled there, privately, until they
are fixed. To report a vulnerability, email hello@vukisha.co.ke rather than opening a public issue.

## CI layout

| Cadence | Jobs | Budget |
|---|---|---|
| Every PR | Engine unit, fixture snapshot, two-engine parity, matcher properties, knowledge base validation, app unit and webhook replay, API contract, Nuxt component | Under five minutes wall clock |
| Nightly | Native-image fixture run, performance budget, benchmark corpus, visual regression, end-to-end, browser scanner parity | Under forty minutes |
| Weekly | Closed-loop fix, link check, corpus re-pin and re-score | Uses our own API key for the fix test |

The engine and knowledge base are public repositories, so per-PR and nightly jobs run on free GitHub Actions minutes. The closed-loop fix test is the only job with a token cost and it is capped by the weekly cadence.
