# Status: what is built, what is pending

Last updated 2026-09-23. Everything below was verified by running it on that date, not by reading the code.

## One-line status

The scanner works end to end from a browser, a command line, CI, a GitHub App and now a coding
agent. The knowledge base is watched daily. What is thin is reach: ten providers, five languages
for call sites, and a native binary for Linux only.

## Built and verified

| Component | State | Evidence |
|---|---|---|
| Knowledge base | 10 providers, 77 change records, 33 fixtures | `knowledge/scripts/validate` reports 0 errors |
| Java engine | 3 detection layers, matcher, validator, `Matcher.lookup` | 174 tests pass |
| TypeScript engine | Same three layers, runs in the browser | 48 unit tests pass |
| Two-engine parity | Byte-identical output on every fixture | `npm run parity` passes |
| Command line | `scan`, `match`, `validate`, `mcp` | 21 tests pass; release smoke test drives the native binary |
| MCP server | `check_api`, `upcoming_deprecations`, `scan_repository` | 17 tests; a real Claude Code session used it unprompted |
| Server app | Webhooks, scan worker, REST API, fix dispatch, runtime observation, org blast radius | 69 tests pass |
| Web site | Scanner, calendar with subscriptions, dashboard, finding detail, CI and Agents pages, live scan links | 18 browser tests pass |
| Feeds | iCalendar (all and per provider), Atom, open JSON | 11 tests; parsed by the `icalendar` library; served correctly by nginx 1.27 |
| Knowledge watch | Daily fetch of 26 cited pages, issue on news, optional agent-drafted pull request | 16 tests; two live runs over every source, the second reporting no change |
| Open-source study | Cohort runner and aggregate summary | First cohort: `study/2026-09-openai-top50` |
| Relay worker | Tarball streaming with permissive origins | 9 tests pass |
| Deployment | Live behind a Cloudflare Tunnel, five containers, no inbound ports | Runbooks in the private operations repository |

Total: 264 Java tests (174 engine, 21 CLI, 69 app), 75 TypeScript unit tests, 18 browser tests, 9 relay tests.

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

1. **Native binaries for macOS and Windows.** Releases ship `docswatcher-linux-x64` and a portable jar. On a Mac the jar needs Java 25, which is a poor first step for someone adding an MCP server to their editor.
2. **Scan speed on large repositories.** A repository of a few hundred megabytes takes minutes in the JVM. Fine in CI, slow for an agent's `scan_repository` call.
3. **Call-site languages.** Java, Python, TypeScript, JavaScript and Go. Ruby, PHP and C# are found through manifests and literals only.
4. **Providers.** Ten. Azure OpenAI, Mistral, Twitch, Meta Graph and PayPal are the obvious next ones; each is a `provider.yaml`, a detector table and change records.
5. **Knowledge watch coverage.** It watches pages already cited. A provider's brand-new deprecation page is found only if its changelog, which is watched, links to it.
6. **Status badge with a count.** Deliberately not built; see ADR 0004.

## Technical notes

- **JDK native access warning.** The Java tree-sitter binding loads native libraries, and newer JDKs log a restricted-method warning. Silence it with `--enable-native-access=ALL-UNNAMED`.
- **jsDelivr branch caching.** Through the jsDelivr route a branch can lag its newest commit by minutes. The relay and GitHub API routes resolve the commit directly.
- **Offline scanning.** In a sandbox that blocks outbound requests, the site still scans its vendored real repositories and local folders.
