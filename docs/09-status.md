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

## Roadmap & Future Enhancements

The following initiatives represent current areas of active development:

### 1. Automated Upstream Ingestion Pipelines
Developing scheduled scrapers and diff monitors for provider OpenAPI specifications, documentation portals, and changelogs to automatically generate change-record pull requests against the knowledge base for maintainer review.

### 2. Runtime Telemetry Observation
Adding an OpenTelemetry middleware processor to passively observe live `Deprecation` and `Sunset` HTTP response headers directly from production traffic, correlating runtime invocations with static call-site inventories.

### 3. Multi-Repository Organization Blast Radius
Expanding the web dashboard to aggregate scan results across hundreds of repositories within an organization, allowing platform teams to view the blast radius of a single provider sunset across their entire fleet.

### 4. Language & Ecosystem Coverage
Broadening Tree-sitter AST queries to include Ruby, PHP, and C# call sites, augmenting current manifest and literal detection.

## Technical Considerations

- **JDK Native Access Warning**: The Java Tree-sitter binding loads native shared libraries, causing newer JDKs to log a restricted-method access warning. This can be silenced by passing `--enable-native-access=ALL-UNNAMED`.
- **jsDelivr Branch Caching**: When scanning via the jsDelivr route, branch references may occasionally lag newest commits by minutes due to CDN edge caching. The fallback GitHub API and relay routes provide real-time commit resolution.
- **Sandboxed Execution**: In constrained or offline environments, client-side scans run completely offline using vendored knowledge-base fixtures.

