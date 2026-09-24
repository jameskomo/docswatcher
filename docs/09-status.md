# Status: what is built, what is pending

Last updated 2026-09-24. Everything below was verified by running it on that date, not by reading the code.

## One-line status

The scanner works end to end from a browser, a command line, CI, a GitHub App and now a coding
agent. The knowledge base is watched daily. What is thin is reach: ten providers, five languages
for call sites, and no native binary for Intel Macs.

## Built and verified

| Component | State | Evidence |
|---|---|---|
| Knowledge base | 10 providers, 93 change records, 37 fixtures | `knowledge/scripts/validate` reports 0 errors |
| Java engine | 3 detection layers, exclusion rules, matcher, validator, `Matcher.lookup` | 198 tests pass |
| TypeScript engine | Same three layers and the same exclusion rules, runs in the browser | Part of the 99 TypeScript unit tests below |
| Two-engine parity | Byte-identical output on every fixture | `npm run parity` passes |
| Path exclusion | `.gitignore` files, a root `.docswatcherignore`, and `--exclude` | 21 shared cases replayed by both engines |
| Command line | `scan`, `match` (with `--report` and `--inventory` from one scan), `validate`, `mcp`, and `--exclude` | 28 tests pass; the release smoke-tests the native binary on Linux and macOS |
| MCP server | `check_api`, `upcoming_deprecations`, `scan_repository` | Covered by the CLI tests above, including the shared `check_api` cases; a real Claude Code session used it unprompted |
| Server app | Webhooks, scan worker, REST API, fix dispatch, runtime observation, org blast radius | 108 tests, run in CI (they need Postgres); fix pull requests run an agent that can only edit the files a finding names |
| Web site | Scanner, calendar with subscriptions, dashboard, finding detail with linked evidence, CI, Agents and Teams pages, live scan links | 30 browser tests pass |
| Feeds | iCalendar (all and per provider), Atom, open JSON | 11 tests; parsed by the `icalendar` library; served correctly by nginx 1.27 |
| Knowledge watch | Daily fetch of 26 cited pages, issue on news, optional agent-drafted pull request | 16 tests; two live runs over every source, the second reporting no change |
| Open-source study | Cohort runner and aggregate summary | First cohort: `study/2026-09-openai-top50` |
| Try it (Agents page) | Claude, OpenAI (GPT, Codex) or Gemini with the visitor's own key, answered with and without `check_api` | 4 browser tests against mocked providers; `check_api` held to the CLI by 26 shared cases |
| Early access | Teams page form, stored in Postgres, emailed to the owner by the `notify/` worker | 7 app tests, 3 notifier tests, 3 worker tests; checked live |
| Relay worker | Tarball streaming with permissive origins. Not used by the deployed site; kept for self-hosters | 10 tests pass |
| Deployment | Live behind a Cloudflare Tunnel, five containers, no inbound ports | Runbooks in the private operations repository |

Total: 354 Java tests (218 engine, 28 CLI, 108 app), 132 TypeScript unit tests, 30 browser tests,
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

1. **Native binaries for Windows and Intel Macs.** Releases ship native binaries for Linux x64 and macOS arm64, and a portable jar. The Windows build compiles but does not yet carry its grammar libraries; GraalVM Community no longer builds for macOS x64. Both need the jar and Java 25, which is a poor first step for someone adding an MCP server to their editor.
2. **Call-site languages.** Java, Python, TypeScript, JavaScript and Go. Ruby, PHP and C# are found through manifests and literals only.
3. **Providers.** Ten. Azure OpenAI, Mistral, Twitch, Meta Graph and PayPal are the obvious next ones; each is a `provider.yaml`, a detector table and change records.
4. **Knowledge watch coverage.** It watches pages already cited. A provider's brand-new deprecation page is found only if its changelog, which is watched, links to it.
5. **Status badge with a count.** Deliberately not built; see ADR 0004.

## Technical notes

- **JDK native access warning.** The Java tree-sitter binding loads native libraries, and newer JDKs log a restricted-method warning. Silence it with `--enable-native-access=ALL-UNNAMED`.
- **jsDelivr branch caching.** Through the jsDelivr route a branch can lag its newest commit by minutes. The relay and GitHub API routes resolve the commit directly.
- **Offline scanning.** In a sandbox that blocks outbound requests, the site still scans its vendored real repositories and local folders.
