# Features

Every capability DocsWatcher has today, what it does, how to use it, and how it works. Verified on 2026-09-18; sections 11 to 13 on 2026-09-23.

## Contents

1. [Detection](#1-detection)
2. [The inventory](#2-the-inventory)
3. [Matching](#3-matching)
4. [The knowledge base](#4-the-knowledge-base)
5. [Two engines and parity](#5-two-engines-and-parity)
6. [The command line](#6-the-command-line)
7. [The web site](#7-the-web-site)
8. [The server app](#8-the-server-app)
9. [The relay](#9-the-relay)
10. [Cross-cutting properties](#10-cross-cutting-properties)
11. [Coding agents](#11-coding-agents)
12. [Feeds and sharing](#12-feeds-and-sharing)
13. [Knowledge watch](#13-knowledge-watch)
14. [Excluding paths](#14-excluding-paths)

## 1. Detection

Three layers run in order of cost. Every rule is data in the knowledge base, not code.

### Manifest layer

Reads dependency files and records which provider SDKs the project declares. Cheapest and most reliable.

| Ecosystem | Files read | Match | Version |
|---|---|---|---|
| npm | `package.json` | key in `dependencies` or `devDependencies` | the value string |
| pypi | `requirements*.txt`, `pyproject.toml` | package name, case-insensitive | pinned version if written |
| maven | `pom.xml` | `groupId:artifactId` | the `<version>` text |
| go | `go.mod` | module path in a `require` | the version token |
| rubygems | `Gemfile` | first string argument of `gem` | second string if present |

A manifest hit does two things: it produces an `sdk_package` contract, and it unlocks that provider's call-site rules.

**The manifest gate is the main precision guard.** Call-site rules never run for a provider whose package is absent from every manifest. A file that happens to define a method called `create` on something called `Source` is not a Stripe call unless the project actually depends on Stripe.

### Literal layer

Regular expressions over file contents. This is where model IDs and pinned versions are found.

```yaml
- id: openai.literal.model-gpt
  kind: model
  files: ["**/*"]
  exclude: ["**/node_modules/**", "**/target/**", "**/*.lock"]
  pattern: '\b(gpt-[0-9][0-9a-z.-]*[0-9a-z]|dall-e-[0-9]|whisper-[0-9]+)\b'
  key: "$1"
  confidence: medium
```

Patterns must use the subset of regular expressions that Java and JavaScript both support, because two engines run them. The validator rejects possessive quantifiers, lookbehind, inline flags, and most character classes. Lookahead is allowed.

### Call-site layer

Tree-sitter queries against the parsed syntax tree. This is what turns an SDK method into the endpoint it calls.

```yaml
- id: stripe.java.source-create
  language: java
  kind: sdk_method
  requires: com.stripe:stripe-java
  query: |
    (method_invocation
      object: (identifier) @cls
      name: (identifier) @m
      (#eq? @cls "Source") (#eq? @m "create")) @call
  key: "Source.create"
  maps_to:
    kind: endpoint
    key: "POST /v1/sources"
  confidence: high
```

`maps_to` emits a second contract with the same evidence, which is how `Source.create` also becomes `POST /v1/sources`.

Six grammars ship: java, python, typescript, tsx, javascript, go. Language is chosen by file extension.

### Contract kinds

`sdk_package`, `sdk_method`, `endpoint`, `model`, `api_version`, `graphql_operation`, `webhook`. Adding one is a schema version bump.

### Confidence and the downgrade rule

| Level | When |
|---|---|
| high | Manifest and call-site hits |
| medium | Literal hits in ordinary source |
| low | Every evidence location is a documentation or test path |

Documentation paths end in `.md`, `.rst`, `.txt`, or `.adoc`. Test paths contain a segment like `test`, `spec`, or `fixtures`, or a filename like `*.test.js`. Manifest evidence is never downgraded, because `requirements.txt` ends in `.txt` but is not documentation.

Low-confidence contracts produce no findings by default. This is what keeps a provider name in a README from raising an alert.

### Skipping

Directories named `node_modules`, `target`, `dist`, `build`, `.git`, `vendor`, and `.venv` are never entered. Files over 1 MB are skipped and counted.

## 2. The inventory

A scan produces one inventory document. It is the single seam between every component.

```json
{
  "id": "stripe:endpoint:POST /v1/sources",
  "provider": "stripe",
  "kind": "endpoint",
  "key": "POST /v1/sources",
  "confidence": "high",
  "evidence": [
    {
      "path": "src/main/java/pay/SourceClient.java",
      "line": 14,
      "column": 12,
      "snippet": "return Source.create(params, OPTIONS);",
      "detector": "stripe.java.source-create",
      "layer": "callsite"
    }
  ],
  "context": {
    "sdk": { "ecosystem": "maven", "package": "com.stripe:stripe-java", "version": "29.0.0" },
    "apiVersion": "2020-08-27"
  }
}
```

Lines and columns are 1-based. For a literal rule the column is where the capture group starts, not where the whole match starts. Snippets are the source line trimmed and capped at 200 characters.

`context.sdk` appears when exactly one manifest rule for that provider matched. `context.apiVersion` appears when exactly one version contract exists for that provider. Otherwise the field is omitted rather than set to null.

Contracts sort by id, evidence sorts by path then line. Ordering is part of the schema because two engines are compared byte for byte.

## 3. Matching

The matcher is a pure function from an inventory plus the knowledge base to a list of findings.

| Change status | Produces findings |
|---|---|
| `active` | Yes |
| `expired` | Yes |
| `draft` | No |
| `withdrawn` | No |

Expired changes still fire. Code calling a model that was retired last year is already broken, and hiding that would be wrong.

**Endpoint matching.** A literal rule that cannot know the HTTP method writes `ANY`. A change affecting `ANY /v1/threads` matches any method on that path. A change affecting `POST /v1/sources` matches `POST /v1/sources` and `ANY /v1/sources`.

**Severity.** `breaking` means calls fail after the date. `warning` means behaviour differs. `info` means nothing fails.

**Days remaining** is computed at match time and goes negative after the effective date.

Findings sort by effective date ascending with nulls last, then by severity.

## 4. The knowledge base

Open source, community-editable, and the part that is hardest to copy.

| Provider | Change records |
|---|---|
| OpenAI | 32 |
| Google AI | 12 |
| Shopify | 11 |
| Anthropic | 10 |
| AWS SDK | 3 |
| GitHub | 2 |
| SendGrid | 2 |
| Slack | 2 |
| Twilio | 2 |
| Stripe | 1 |

Every record was researched from the provider's own deprecation page on 2026-09-18 and carries its source URL and the date observed.

A change record:

```yaml
id: openai-assistants-api-shutdown-2026
provider: openai
kind: sunset
severity: breaking
title: Assistants API shut down
affects:
  - kind: endpoint
    match: "ANY /v1/assistants"
  - kind: sdk_method
    match: "beta.assistants.create"
announced: 2025-08-20
effective: 2026-08-26
sources:
  - kind: provider_page
    url: https://developers.openai.com/api/docs/deprecations
    observed: 2026-09-18
migration:
  replacement: "POST /v1/responses"
  guide: https://developers.openai.com/api/docs/deprecations
  effort: large
status: expired
```

### Fixtures

33, each a tiny repository plus the exact inventory and findings it must produce. Ten are negatives: a provider named in a README, a mocked client in a test file. They exist to pin down false positives, and every provider needs at least one.

### The central invariant

**No change without a fixture.** A deprecation we cannot demonstrate detecting is not published. The validator enforces this.

### Validator checks

Errors: schema violations, bad enum values, effective before announced, an active record whose date has passed, a regex outside the shared dialect, a duplicate detector id, a tree-sitter query that does not parse, a change record no fixture references, a provider with no negative fixture.

Warnings: an individual `affects` entry that no fixture exercises.

```
$ knowledge/scripts/validate
Knowledge local: 10 providers, 77 change records, 33 fixtures
OK · 0 errors, 0 warnings
```

## 5. Two engines and parity

The same detection rules are executed by two interpreters: one in Java for the CLI and server, one in TypeScript for the browser.

This exists because the browser scanner is only trustworthy if it agrees exactly with the one running in CI. Shared logic would need a shared language; shared data does not.

Parity is enforced on every fixture:

```
$ ./knowledge/scripts/parity
fixture                                contracts  inventory  findings
anthropic-java-messages                 4          PASS       PASS (3)
openai-model-zoo                        32         PASS       PASS (32)
stripe-java-sources                     4          PASS       PASS (2)
...
all checks passed
```

The payoff showed up immediately. A false positive, where a Stripe version string `2022-11-15` matched a Shopify rule expecting `2022-11`, was fixed by editing one line of YAML. Both engines picked it up with no code change.

## 6. The command line

```
docswatcher match /path/to/repo --knowledge knowledge --format text
```

Text output is shaped as a drift report: grouped by severity, each finding naming the provider, what changed, the effective date and days remaining, the evidence locations, and the migration one-liner with a guide link.

JSON is the default and is what CI should consume.

**The exit code is the contract.** `match` exits 1 when any breaking finding is open. Add it as a build step and the build fails when someone introduces a call to a sunset endpoint.

A native binary builds via `./mvnw -Pnative -pl cli -am package`, starts in under two seconds, and behaves identically.

## 7. The web site

Nuxt 4, exported as static files, with the engine running entirely in the browser.

### The scanner

Three input modes on the landing page.

**Sample repository.** Two real public repositories are vendored in at a pinned commit, plus the fourteen fixtures. The real ones are the default because a genuine project with genuine findings is more convincing than a hand-built one.

| Bundled repository | Commit | Result |
|---|---|---|
| `openai/openai-quickstart-python` | ec8890d | 3 breaking findings |
| `Shopify/shopify-app-template-node` | 4e73e21 | 1 breaking finding, an Admin API version unsupported since 2025 |

Refresh them with `node web/scripts/vendor-repos.mjs --refresh`.

**GitHub URL.** Tries three routes and uses the first the host permits:

| Route | Rate limit |
|---|---|
| Your relay, if `NUXT_PUBLIC_RELAY_URL` is set | None |
| jsDelivr | None, but branch refs are cached |
| GitHub API plus raw file reads | 60 requests per hour per address, two per scan |

jsDelivr leads because it has no hourly ceiling. If every route is blocked, the page names each one rather than showing a bare fetch error.

**Local folder.** Reads a directory you pick. Nothing leaves the browser.

### The calendar

`/calendar` lists every deprecation we track, grouped by the month it takes effect, with a toggle for ones already past. Generated from the knowledge base at build time. This is the public SEO surface described in the vision document.

### The dashboard

`/app` renders the last scan.

- **The provider map.** Every external service as a node, sized by call sites, coloured by health. The picture most engineering leads have never seen of their own system.
- **The horizon.** A twelve-month timeline of effective dates with the affected contracts beneath each.
- **The inventory browser.** Every contract found, including the ones nobody remembers adding.

### Finding detail

`/app/findings/[id]` shows the evidence with source snippets, the change summary, and the migration notes. The fix button copies a ready-to-paste prompt for a coding agent, containing the finding, every evidence location with its snippet, and the provider's migration block.

Snooze and the production flag are per-browser here. The server app stores them properly.

### Presentation

Light and dark themes from CSS custom properties, a phone-width layout with no horizontal scroll at 375 pixels, hash routing, and relative asset URLs so the export works at any path on any static host.

## 8. The server app

Spring Boot 4 on Java 25. This is the GitHub App backend.

### Webhooks

`POST /webhooks/github` verifies `X-Hub-Signature-256` with a constant-time compare, responds 202 immediately, and records work as rows. Handled events: installation created, deleted and suspended; installation repositories; push on the default branch; issues labelled.

Deliveries are de-duplicated so a GitHub retry does not scan twice.

### The scan worker

A polling loop over the `scan_run` table using `SELECT ... FOR UPDATE SKIP LOCKED`, running on virtual threads. There is no queue service, which is deliberate: a table and a loop are free and sufficient.

Each run does a shallow clone at the commit, scans, matches, upserts contracts by repository and id, reconciles findings, posts a check run, and opens one issue per new finding. A finding closes as fixed when its evidence is gone from a later scan.

A rematch re-runs the matcher over stored contracts without cloning, which is what happens when the knowledge base updates.

### Label commands

| Label | Effect |
|---|---|
| `docswatcher:fix` | Dispatches the fix workflow |
| `docswatcher:snooze-30d` | Hides the finding for thirty days |
| `docswatcher:not-in-prod` | Marks it informational |

Snoozes survive rescans because findings are keyed by repository, contract, and change together.

### The fix loop

Adding the fix label dispatches a `repository_dispatch` event into the customer's own repository, carrying the finding, the evidence with snippets, and the migration block. A workflow the app serves at `/api/setup/workflow` runs a coding agent with the customer's own API key, applies the migration, runs the affected tests, and opens a pull request.

**We never spend tokens on remediation.** The customer's agent, the customer's key, the customer's CI. We own the trigger and the context, which is the part that is hard.

## 9. The relay

A Cloudflare Worker that streams a GitHub tarball to a browser with permissive cross-origin headers.

It exists because a browser cannot read a GitHub tarball directly. The redirect target only allows GitHub's own renderer origin, and fetching files one by one exhausts the API limit. This was verified with real requests and recorded in `docs/adr/0003-browser-scanning-and-repo-fetch.md`.

It holds no state. Anonymous responses are cached five minutes by commit. A client `Authorization` header is forwarded to GitHub and never cached, which is how private repositories work. Tarballs over 60 MB are refused.

It is optional. The jsDelivr route covers public repositories without it.

## 10. Cross-cutting properties

**Determinism.** Same input, same bytes out. Ordering is specified, not incidental. This is what makes the parity test possible and snapshot tests meaningful.

**Precision over recall.** A false positive wastes an afternoon and costs you the user. A missed contract is invisible. Negative fixtures exist for exactly this reason, and the manifest gate is the strongest expression of it.

**Everything is data.** Detection rules, deprecations, and fixtures are YAML and JSON in an open repository. Fixing a false positive means editing one line, and both engines pick it up.

**Privacy & client-side performance.** Static hosting, browser-side WebAssembly AST evaluation, and local execution ensure zero proprietary source code exposure.

## 11. Coding agents

`docswatcher mcp` serves the Model Context Protocol on stdio, so Claude Code, Cursor and other
agents can ask before they write an identifier. Three read-only tools:

| Tool | Answers |
|---|---|
| `check_api` | `RETIRED`, `RETIRING` with days left, `CHANGED`, or `NO KNOWN DEPRECATION`, with the replacement |
| `upcoming_deprecations` | Dated shutdowns in a window, optionally for one provider |
| `scan_repository` | The same findings as `match`, for a directory |

`check_api` builds the key the scanner would have extracted and runs it through the same
`Matcher`, so its answer agrees with CI. It accepts loose input (`openai/gpt-4-turbo`, a full API
URL, `POST /v1/assistants`, `openai==0.28`) and only tries a value as an API version when it is
date-shaped, because API version rules compare strings. At startup the server tells the agent to
check identifiers before writing them. Details and the test plan: `docs/14-coding-agents.md`.

## 12. Feeds and sharing

Generated at build time from the knowledge base, served as static files:

- **Calendar.** `/feeds/deprecations.ics` and one per provider. One all-day event per dated record,
  keyed by change ID so edits update rather than duplicate, marked free rather than busy, with
  reminders 30 and 7 days before. The calendar page has subscribe links for Google Calendar,
  Apple Calendar and Outlook.
- **Feed.** `/feeds/deprecations.atom`, newest announcement first.
- **Open data.** `/feeds/deprecations.json`, every live record, readable from any origin.
- **Live scan links.** `/#/?repo=owner/name` scans that repository on arrival, and the address bar
  becomes this link after any GitHub scan. The results offer the link and a README badge that
  points to it (`docs/adr/0004-live-links-not-status-badges.md`).

All three files are pure functions of the knowledge base, so an unchanged knowledge base produces
identical bytes. `docs/15-feeds-and-sharing.md`.

## 13. Knowledge watch

A daily workflow fetches every URL the knowledge base cites, reduces each page to text, and
compares it with the last run. It opens an issue only when a page gains lines that mention a
deprecation or a date, a source fails three runs in a row, a page cannot be read, or a record has
gone 30 days without being re-verified. Snapshots live on the `knowledge-watch` branch. With a
`CLAUDE_CODE_OAUTH_TOKEN` secret, an agent drafts change records from the report into a draft pull
request, with its file access limited to `knowledge/` and a second check in the workflow.
`docs/16-knowledge-watch.md`.

## 14. Excluding paths

Every scan skips what the repository's `.gitignore` files exclude, what a root `.docswatcherignore`
lists, and any `--exclude` patterns (the Action's `exclude` input, MCP `scan_repository`'s
`exclude`). All three use `.gitignore` syntax, later ones win, and an excluded file is never read.
Both engines share one matcher specification, proven by the same cases, and the site's fetchers
apply the ignore files before choosing which 300 files to read, so fixtures cannot crowd out real
code. `docs/18-excluding-paths.md`.

