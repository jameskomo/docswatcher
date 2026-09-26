# Status: what is built, what is pending

Last updated 2026-09-24. Everything below was verified by running it on that date, not by reading the code.

## One-line status

The scanner works end to end from a browser, a command line, CI, a GitHub App and now a coding
agent. The knowledge base is watched daily. What is thin is reach: 27 providers, eight languages
The scanner works end to end from a browser, a command line, CI, a GitHub App, a connected GitLab
group and a coding agent. The knowledge base is watched daily. What is thin is reach: fifteen providers, eight languages
for call sites, and no native binary for Intel Macs.

## Built and verified

| Component | State | Evidence |
|---|---|---|
| Knowledge base | 27 providers, 309 change records, 111 fixtures | `knowledge/scripts/validate` reports 0 errors |
| Java engine | 3 detection layers, exclusion rules, matcher, validator, `Matcher.lookup` | 198 tests pass |
| TypeScript engine | Same three layers and the same exclusion rules, runs in the browser | Part of the 99 TypeScript unit tests below |
| Two-engine parity | Byte-identical output on every fixture | `npm run parity` passes |
| Path exclusion | `.gitignore` files, a root `.docswatcherignore`, and `--exclude` | 21 shared cases replayed by both engines |
| Your own APIs | A repository's `.docswatcher/` records and the organisation's `.docswatcher` repository, in both engines, the CLI (`--knowledge-extra`), the Action (`knowledge`), MCP, the App and the site | 9 shared validation cases replayed by both engines; the `internal-orders-own-records` fixture passes parity; CLI, App and browser tests |
| Command line | `scan`, `match` (with `--report` and `--inventory` from one scan), `validate`, `mcp`, and `--exclude` | 28 tests pass; the release smoke-tests the native binary on Linux, macOS and Windows |
| MCP server | `check_api`, `upcoming_deprecations`, `scan_repository` | Covered by the CLI tests above, including the shared `check_api` cases; a real Claude Code session used it unprompted |
| Server app | Webhooks, scan worker, REST API, fix dispatch, runtime observation with per-repository ingest tokens, org blast radius, sign-in with GitHub with per-organisation and per-repository access (ADR 0008). Each scan runs in a process of its own with a heap cap and a timeout, so a parser crash fails one run, not the server; runs left running by a restart are failed and retried once (ADR 0011); GitLab beside the GitHub App: sign-in, push scans, issues and commit statuses (ADR 0012) | 141 tests, run in CI (they need Postgres); the OAuth round trip runs against a scripted GitHub; fix pull requests run an agent that can only edit the files a finding names |
| Web site | Scanner, calendar with subscriptions, dashboard (browser scan signed out; the organisation dashboard signed in), finding detail with linked evidence, CI, Agents and Teams pages, live scan links | 51 browser tests pass; the organisation dashboard's run against a mocked API |
| Organisation dashboard | Sign in with GitHub or GitLab; overview, provider map, horizon, repositories, findings with snooze, not-in-production and fix, blast radius of one change; what actually runs per repository, with ingest tokens and the setup guide | Built and tested; live once the App's client secret and the `/auth/` and `/api/` routes are deployed |
| GitLab | Sign-in with GitLab, groups and projects connected with an encrypted maintainer access token, push scans, an issue per finding, a commit status, issue labels and closes, GitLab groups on the organisation dashboard (ADR 0012) | 51 of the app's tests, against a scripted GitLab (MockRestServiceServer): sign-in, connecting, webhook tokens and namespace bounds, a scan end to end with its issue, close and status; 3 browser tests. Not yet run against a real GitLab instance |
| Feeds | iCalendar (all and per provider), Atom, open JSON | 11 tests; parsed by the `icalendar` library; served correctly by nginx 1.27 |
| Knowledge watch | Daily fetch of every cited page (68 since the 2026-09-24 providers), issue on news, optional agent-drafted pull request | 16 tests; two live runs over the 26 sources cited before then, the second reporting no change; every page the new providers cite answered 200 when they were added |
| Open-source study | Cohort runner and aggregate summary | First cohort: `study/2026-09-openai-top50` |
| Try it (Agents page) | Claude, OpenAI (GPT, Codex) or Gemini with the visitor's own key, answered with and without `check_api` | 4 browser tests against mocked providers; `check_api` held to the CLI by 30 shared cases |
| Early access | Teams page form, stored in Postgres, emailed to the owner by the `notify/` worker | 7 app tests, 3 notifier tests, 3 worker tests; checked live |
| Alerts before the date | Team alerts by email and Slack at 30 and 7 days (configurable) from a daily job, settings on the organisation dashboard; public email alerts from the calendar with double opt-in and one-click unsubscribe (ADR 0010) | 31 app tests against Postgres with Brevo and Slack mocked, including idempotency, catch-up, retry and access control; 3 browser tests. Not yet live: needs the Brevo key and the `/subscribe` and `/unsubscribe` routes |
| Relay worker | Tarball streaming with permissive origins. Not used by the deployed site; kept for self-hosters | 10 tests pass |
| Deployment | Live behind a Cloudflare Tunnel, five containers, no inbound ports | Runbooks in the private operations repository |

Total: 579 Java tests (332 engine, 38 CLI, 209 app), 247 TypeScript unit tests, 49 browser tests,
10 relay tests and 3 notify tests. The app's tests need Postgres and run in CI rather than on a developer's machine;
every other number here was produced by running that suite.

### Verified against real repositories

| Repository | Files | Findings |
|---|---|---|
| `anthropics/anthropic-quickstarts` | 467 | 10 |
| `openai/openai-quickstart-python` | 13 | 3 |
| `Shopify/shopify-app-template-node` | 30 | 1 |
| `stripe-samples/accept-a-payment` | 650 | 0 |

The two zero-finding repositories are as important as the others. They are evidence the scanner does not fire indiscriminately. The study extends this to fifty repositories chosen by a rule written down before the run.

## Not built yet

Ordered by what is most likely to cost a user today.

1. **Native binary for Intel Macs.** Releases ship native binaries for Linux x64, macOS arm64 and Windows x64, and a portable jar. GraalVM Community no longer builds for macOS x64, so an Intel Mac needs the jar and Java 25, which is a poor first step for someone adding an MCP server to their editor.
2. **Providers.** Twenty-seven. Azure OpenAI, Mistral, Meta Graph, PayPal and Twitch were added on 2026-09-24; Square, Paystack, X, LinkedIn, YouTube, Discord, Mailchimp, Google Maps Platform, Firebase, Salesforce, HubSpot and Cohere on 2026-09-26. Salesforce names a release rather than a day for its retirements, so its two records carry no effective date. Some of what they deprecate has no date to record: PayPal publishes no removal date for any deprecated REST resource, Azure OpenAI none for its dated api-versions, and Meta's Instagram and Page Insights metric removals are field names, which no contract kind describes. Each further provider is a `provider.yaml`, a detector table and change records.
3. **Knowledge watch coverage.** It watches pages already cited. A provider's brand-new deprecation page is found only if its changelog, which is watched, links to it.
4. **Status badge with a count.** Deliberately not built; see ADR 0004.
5. **Alerts beyond email and Slack.** Microsoft Teams, webhooks to anything else, and alerts for
   runtime-only evidence are not built. Team alerts are sent from one app process; two processes
   could both send before either records.
5. **Fix pull requests on GitLab.** A GitLab project gets issues, a commit status and the dashboard, but no fix pull request: the fix loop is a GitHub `repository_dispatch`, and a GitLab pipeline trigger would be its counterpart. Findings are not posted as merge request notes either; default-branch scans have no merge request, and the commit status is what merge requests show (ADR 0012).
6. **Your own APIs in the App's dashboard.** Issues and check runs carry a team's own change records in full, but the dashboard and fix pull requests look change records up in the bundled knowledge, so they show such a finding by its change id. The App reads only the owner's `.docswatcher` repository.

## Technical notes

- **Scan process start.** Scanning in a process of its own (ADR 0011) adds a JVM start per scan: about 0.6 s on a quiet 8-core development machine and up to 1.5 s while other builds ran, from a plain classpath and through the Spring Boot jar alike. The App logs the figure at startup. `docswatcher.scan.isolation: in-process` removes it and the protection with it.
- **JDK native access warning.** The Java tree-sitter binding loads native libraries, and newer JDKs log a restricted-method warning. Silence it with `--enable-native-access=ALL-UNNAMED`.
- **jsDelivr branch caching.** Through the jsDelivr route a branch can lag its newest commit by minutes. The relay and GitHub API routes resolve the commit directly.
- **Offline scanning.** In a sandbox that blocks outbound requests, the site still scans its vendored real repositories and local folders.
