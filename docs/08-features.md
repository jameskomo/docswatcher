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
15. [Your own APIs](#15-your-own-apis)

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
| packagist | `composer.json` | key in `require` or `require-dev` | the value string |
| nuget | `*.csproj` | `<PackageReference Include>`, case-insensitive | `Version` attribute or element |

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

Nine grammars ship: java, python, typescript, tsx, javascript, go, ruby, php, csharp. Language is chosen by file extension (`.rb`, `.php` and `.cs` for the last three). Both engines pin the same grammar versions, so a query means the same thing in the browser and on the command line.

### Contract kinds

`sdk_package`, `sdk_method`, `endpoint`, `model`, `api_version`, `graphql_operation`, `webhook`. Adding one is a schema version bump.

### Confidence and the downgrade rule

| Level | When |
|---|---|
| high | Manifest and call-site hits |
| medium | Literal hits in ordinary source |
| low | Every evidence location is a documentation or test path |

Documentation paths end in `.md`, `.rst`, `.txt`, or `.adoc`. Test paths contain a segment like `test`, `spec`, or `fixtures`, or a filename like `*.test.js` or `*Tests.cs`. Manifest evidence is never downgraded, because `requirements.txt` ends in `.txt` but is not documentation.

Low-confidence contracts produce no findings by default. This is what keeps a provider name in a README from raising an alert.

### Skipping

Directories named `node_modules`, `target`, `dist`, `build`, `.git`, `vendor`, and `.venv` are never entered. Files over 1 MB are skipped and counted.

The whole scan is bounded as well: 20,000 files, 200 MB read, and 10 minutes by default, each configurable. A scan that reaches one stops, keeps what it found, and says so: `stats.incomplete` in the inventory, a warning and exit code 4 from the CLI when nothing breaking was found, and an `Incomplete scan` check run that closes nothing in the GitHub App. Details under "Scan limits" in `docs/10-reference.md`.

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
| LinkedIn Marketing API | 50 |
| OpenAI | 42 |
| Mistral AI | 41 |
| Azure OpenAI | 35 |
| Shopify | 16 |
| Cohere | 15 |
| Meta Graph API | 15 |
| Google AI | 13 |
| Google Maps Platform | 11 |
| Twitch | 11 |
| Anthropic | 10 |
| PayPal | 7 |
| HubSpot | 6 |
| X (Twitter) API | 5 |
| Discord API | 4 |
| Firebase | 4 |
| Square | 4 |
| YouTube Data API | 4 |
| AWS SDK | 3 |
| GitHub | 2 |
| Salesforce | 2 |
| SendGrid | 2 |
| Slack | 2 |
| Twilio | 2 |
| Mailchimp | 1 |
| Paystack | 1 |
| Stripe | 1 |

Every record was researched from the provider's own deprecation page or changelog and carries its source URL and the date observed.

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

55, each a tiny repository plus the exact inventory and findings it must produce. Sixteen are negatives: a provider named in a README, a mocked client in a test file. They exist to pin down false positives, and every provider needs at least one.

### The central invariant

**No change without a fixture.** A deprecation we cannot demonstrate detecting is not published. The validator enforces this.

### Validator checks

Errors: schema violations, bad enum values, effective before announced, an active record whose date has passed, a regex outside the shared dialect, a duplicate detector id, a tree-sitter query that does not parse, a change record no fixture references, a provider with no negative fixture, a provider id starting `internal-` (reserved for a team's own records, section 15).

Warnings: an individual `affects` entry that no fixture exercises.

```
$ knowledge/scripts/validate
Knowledge 2026.09.26: 27 providers, 309 change records, 111 fixtures
OK · 0 errors, 20 warnings
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

Pages: the scanner (`/`), the calendar (`/calendar`), the dashboard (`/app`), finding detail, CI
(`/ci`), Agents (`/agents`), Teams (`/teams`) and About (`/about`). Every feature is named on the
home page and linked from the footer, so none is reachable only by knowing its address.

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

**GitLab URL.** The same field takes a GitLab project, on gitlab.com or a self-managed instance
named by its full URL. Nested groups work: `https://gitlab.com/group/subgroup/project`, optionally
followed by `/-/tree/<ref>`. There is one route, the GitLab REST API v4, which answers any origin
with `Access-Control-Allow-Origin: *`:

| Step | Request |
|---|---|
| Resolve the project and its default branch | `GET /api/v4/projects/<url-encoded path>` |
| Pin the ref to a commit | `GET /projects/:id/repository/commits/<ref>` |
| List files | `GET /projects/:id/repository/tree?recursive=true&per_page=100`, following the `Link` header (keyset pagination), at most 50 pages |
| Read files | `GET /projects/:id/repository/files/<path>/raw?ref=<sha>` |

The same budget applies as on GitHub: the ignore files are read first, excluded paths are
dropped, and at most 300 relevant files are read. A scan costs at most about 360 requests, inside
gitlab.com's 500 a minute per address without a token. The rate limit headers are not exposed to
the page, so a 429 ends the scan with a message. It never returns a partial scan. A token, sent
as `PRIVATE-TOKEN` and only to the instance named in the URL, raises the limit and reads private
projects (`read_api` scope). The archive endpoint also allows cross-origin reads, but its size has
no bound, and gitlab.com limits archive downloads far more tightly, so it is not used.

**Which GitLab hosts work.** The browser can only reach a host that the site's
Content-Security-Policy `connect-src` allows. The deployed site allows `https://gitlab.com`. It
cannot list self-managed instances in advance, and it does not allow every origin. A self-managed
GitLab therefore works where the site's policy allows its host. That means a copy you run
yourself, including `npm run dev`, or a deployment whose policy adds the host. Elsewhere the page
catches the policy violation and says so. It names the host and how to get a copy that allows it,
instead of a bare "Failed to fetch". A host that is unreachable for any other reason (a private
network, no CORS) gets its own message.

**Local folder.** Reads a directory you pick. Nothing leaves the browser.

**What DocsWatcher does.** Below the scan results, the home page has one card per capability, each
linking to where it lives: scanning (the languages, and the provider and record counts read from
the knowledge base, never written in), CI (the Action, the GitLab CI template with its Code
Quality report, and the downloads), AI assistants (the MCP tools and Try it), the calendar with its
feeds and email alerts, your own APIs (docs/19-your-own-apis.md), and teams (sign-in with GitHub or
GitLab, the dashboard, alerts, runtime observation, the GitHub App and GitLab integration).

### The calendar

`/calendar` lists every deprecation we track, grouped by the month it takes effect, with a toggle for ones already past. Generated from the knowledge base at build time. This is the public SEO surface described in the vision document.

Beside the calendar feeds, anyone can ask for **email alerts**: an address and, optionally, one
provider. The app emails a confirmation link and nothing else until it is clicked. Then it emails
30 and 7 days before each tracked shutdown in that provider, one digest a day at most, each with
a one-click unsubscribe. Only the address, its providers and its confirmation state are kept, and
there are no tracking pixels (ADR 0010). Where the deployment cannot send email yet, the form shows
the server's reason and points back at the calendar feed. A line under it sends anyone who wants
alerts about their own repositories to the dashboard.

### The dashboard

`/app` has two faces.

**Signed in with GitHub or GitLab: every repository, one view.** Sign-in with GitHub uses the
DocsWatcher GitHub App's own OAuth client (ADR 0008). The dashboard then covers every organisation
where the App is installed, limited to the repositories GitHub lets the person see. Sign-in with
GitLab uses a GitLab OAuth application with the `read_api` scope (ADR 0012). It covers the
connected groups, limited to the projects where the person is Reporter or above. If there are
several organisations, a picker switches between them.

- **Overview.** Repositories, external contracts, open breaking findings and warnings, and the next deadline.
- **The provider map and the horizon** for the whole organisation, the same components as a single scan.
- **Repositories with open findings.** Open one to see its findings, each with three actions:
  snooze for 30 days, mark as not running in production, or request a fix pull request. The
  actions need write access to the repository (Developer on GitLab), the same bar as the issue
  labels. Otherwise the row says so. A fix pull request is GitHub only, and a GitLab row says so.
- **Connect a GitLab group.** Signed in with GitLab, a maintainer enters a group or project path
  and an access token (Maintainer role, `api` scope). DocsWatcher shows the webhook to add, once.
- **The blast radius of one shutdown.** Pick a change and see every repository and location it touches.
- **Warned before the date.** Where the organisation's alerts go: up to ten email addresses and a
  Slack incoming webhook, and how many days ahead (30 and 7 by default). Anyone who sees the
  organisation reads them; writers change them and can send a test. See section 8.
- **What actually runs.** Per repository, from the customer's own OpenTelemetry: the deprecated
  calls production made (endpoint, provider, calls in total and a day, last seen, and the finding
  each confirms or the provider's own Sunset or Deprecation header), and the open endpoint
  findings production has never been seen making. Model findings are counted, not listed as
  unseen, because an HTTP span cannot show a model name. Members with write access create and
  revoke the repository's ingest tokens here; a new token is shown once. Until telemetry arrives
  the screen says so and opens the setup guide (docs/13-runtime-observation.md).

What a person sees is a snapshot GitHub or GitLab gave at sign-in, and it lasts eight hours.
Their token is not kept.

**Signed out: the last scan run in this browser**, as before. Where sign-in is configured, a
"Sign in with GitHub to see your organisation" invitation sits above it, with a GitLab button
beside it where GitLab sign-in is configured too. On a copy of the site
with no app behind it, the invitation does not appear.

- **The provider map.** Every external service as a node, sized by call sites, coloured by health. The picture most engineering leads have never seen of their own system.
- **The horizon.** A twelve-month timeline of effective dates with the affected contracts beneath each.
- **The inventory browser.** Every contract found, including the ones nobody remembers adding.

### Finding detail

`/app/findings/[id]` shows the evidence with source snippets, the change summary, and the migration notes. The fix button copies a ready-to-paste prompt for a coding agent, containing the finding, every evidence location with its snippet, and the provider's migration block.

Snooze and the production flag are per-browser here. Signed in, the organisation dashboard stores them on the server.

### The Agents page and Try it

`/agents` explains the MCP server and lets a visitor try it with their own assistant: pick Claude,
OpenAI (GPT and Codex) or Gemini, paste a key, and ask for code. The same model answers twice,
with and without `check_api`, side by side, with every check it made. The browser calls the
provider directly and answers `check_api` itself; the key never reaches DocsWatcher. The model
list is the key's own, with retiring models marked and never chosen by default. ADR 0006.

### The CI, Teams and About pages

`/ci` is the GitHub Action, GitLab CI and plain-shell guide, with the upgrade notice for releases
up to v0.2.1, and points at the GitHub App and GitLab integration for watching instead. `/teams`
lists what stays free, then what teams get as built features, each card linking to where it lives:
the dashboard, alerts before the date, the GitHub App and GitLab, runtime observation and your own
APIs. "How to start" says how: an owner installs the GitHub App, or a GitLab maintainer connects a
group from the dashboard, then everyone signs in. The in-page early-access form (ADR 0005) is for
teams that want help getting started or pricing. It also carries the runtime observation setup guide: a Collector
config that keeps only outbound HTTP client spans and strips them to what DocsWatcher reads, the
header capture settings for the Java, Python and Node SDKs, and what is sent and kept. The same
guide sits in the dashboard, pointed at the deployment serving it.

`/about` explains the problem and the scan, where code goes (nowhere, for the browser and CI; the
App and GitLab integration clone on the server by design), and a "What does not exist yet" list
kept in step with the "Not built yet" list in `docs/09-status.md`.

### The footer

Every page ends with links to every feature, in four groups. Use it: the scanner, the calendar,
email alerts, CI on GitHub and on GitLab, the AI assistant setup and Try it. For teams: sign-in
(the dashboard), the Teams page, the runtime observation setup and early access. Subscribe and
build on it: the three feeds, and the your-own-APIs and runtime observation docs. Open source: the
source, the latest release, the documentation, the knowledge base and how it works.

### Presentation

Every page spans the header's width, so its edges line up with the navigation; only running text keeps a reading measure. Icons are Google's Material icons, drawn in the text colour (`web/app/components/Icon.vue`), never emoji. The scan choices are separate bordered buttons that wrap on a phone.

Light and dark themes from CSS custom properties, a phone-width layout with no horizontal scroll at 375 pixels, hash routing, and relative asset URLs so the export works at any path on any static host.

## 8. The server app

Spring Boot 4 on Java 25. This is the backend for the GitHub App and for connected GitLab groups
(ADR 0012).

### Webhooks

`POST /webhooks/github` verifies `X-Hub-Signature-256` with a constant-time compare, responds 202 immediately, and records work as rows. Handled events: installation created, deleted and suspended; installation repositories; push on the default branch; issues labelled, closed and reopened.

Deliveries are de-duplicated so a GitHub retry does not scan twice.

`POST /webhooks/gitlab` takes GitLab's push and issue hooks. GitLab does not sign bodies, so the
`X-Gitlab-Token` header must be the webhook token DocsWatcher issued to a connection. Only its
SHA-256 is stored, and the comparison is constant-time. The connection's namespace bounds what the
event may touch, judged by the stored project path, never the payload's. A project new to a
connected group is checked with GitLab and adopted on its first push. Retries are de-duplicated on
`Idempotency-Key` or `X-Gitlab-Event-UUID`.

### Alerts before the date

A daily job (06:00 UTC) sends each organisation with alerts on one digest per channel: one Slack
message, and one email per address. A digest lists the open findings that have just come within
one of the organisation's thresholds, grouped by shutdown, with the repositories, links to the
lines at the commit last scanned, the migration guide and the dashboard. Snoozed (until the snooze
ends), not in production, not affected and fixed findings are left out.

Nothing is sent twice: what was sent is recorded per finding, date, threshold and channel once
the channel accepted it. A day the job missed is caught up once, with one warning at the nearest
threshold, and a failed channel is retried the next day. Email goes through Brevo as plain text;
Slack only to `https://hooks.slack.com/services/...`. Every email has a link that takes its
address off the list. ADR 0010.

### GitLab connections

A GitLab group or project is connected with a group or project access token its maintainer creates
for DocsWatcher. GitLab must confirm the token has the `api` scope and the Maintainer role. The
token is stored AES-256-GCM encrypted under a key held in a secret file, never in plain text. A
connection is stored like an installation and its projects like repositories, so every scan,
finding and dashboard query is shared with GitHub. Connecting the same path again replaces the
token. Removing a connection needs the owner or a GitLab Maintainer of the namespace.

### The scan worker

A polling loop over the `scan_run` table using `SELECT ... FOR UPDATE SKIP LOCKED`, running on virtual threads. There is no queue service, which is deliberate: a table and a loop are free and sufficient.

Each run does a shallow clone at the commit, scans, matches, upserts contracts by repository and id, reconciles findings, posts a check run (a `DocsWatcher` commit status on GitLab), and opens one issue per new finding. The scan reaches GitHub or GitLab through a small `Forge` interface, so everything else is the same code for both. A finding closes as fixed when its evidence is gone from a later scan. A scan stopped by a scan limit closes nothing: what it did not see is carried forward until a complete scan.

A rematch re-runs the matcher over stored contracts without cloning, which is what happens when the knowledge base updates.

### Label commands

| Label | Effect |
|---|---|
| `docswatcher:fix` | Dispatches the fix workflow |
| `docswatcher:snooze-30d` | Hides the finding for thirty days |
| `docswatcher:not-in-prod` | Marks it informational |
| `docswatcher:not-affected` | Marks it not affected: the change does not touch this code |

Closing a finding's issue by hand says the same as the not-affected label, and reopening the issue opens the finding again. The App's own closes, sent when evidence disappears, are told apart by their bot sender and ignored. Every command needs write permission from the person who sent it.

On GitLab, the same labels, closes and reopens work from Developer and above, except the fix label. DocsWatcher's own closes come from the access token's bot user and are ignored.

Snoozes and verdicts survive rescans because findings are keyed by repository, contract, and change together. A not-affected finding stays recorded but no longer fails the check run.

### The fix loop

Adding the fix label dispatches a `repository_dispatch` event into the customer's own repository, carrying the finding, the evidence as structural locators only — path, line and column, never the matched source line — and the migration block. A workflow the app serves at `/api/setup/workflow` runs a coding agent with the customer's own API key, applies the migration, runs the affected tests, and opens a pull request.

**We never spend tokens on remediation.** The customer's agent, the customer's key, the customer's CI. We own the trigger and the context, which is the part that is hard.

### Runtime observation

`POST /api/runtime/otlp/v1/traces` takes OTLP/JSON from a customer's Collector and keeps one row
per repository, day and endpoint, only for endpoints the scanner found or that carried a
Deprecation or Sunset header. Each repository has its own ingest tokens, stored as SHA-256
hashes: a token names its repository, so the telemetry need not, and it cannot write into any
other repository or reach any other route. The owner token still works. The findings API adds a
`runtime` block to a finding production has been seen making, and
`/api/repos/{id}/runtime/summary` gives the dashboard its view (docs/13-runtime-observation.md).

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


## 15. Your own APIs

A team describes its internal services' deprecations in the knowledge base's own format, in a
`.docswatcher/` directory: `providers/internal-<name>/provider.yaml`, `detectors.yaml` and
`changes/*.yaml`. Every surface reads the scanned repository's `.docswatcher/` and adds it to the
bundled knowledge; an organisation shares records through the `.docswatcher/` of a repository named
`.docswatcher`, which the GitHub App reads for every repository of the owner (and rescans them all
when it changes), and which the CLI and the Action take as `--knowledge-extra` and `knowledge`.
Records are validated with the knowledge base's rules, with `internal-` ids, optional manifests, no
fixtures, and date/status disagreements as warnings. Any error keeps all of them out and is
reported: the CLI exits 3, the App says so in its check run and keeps their findings open, the site
and the MCP server say so in every answer. Both engines are held to the same messages and, by the
fixture `internal-orders-own-records`, to the same results. `docs/19-your-own-apis.md`,
ADR 0009.
