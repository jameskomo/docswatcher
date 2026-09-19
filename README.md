# DocsWatcher

Dependabot for the APIs you call, not the packages you install.

Scan a repository for every external contract it depends on. Match the inventory against an open knowledge base of provider deprecations. Surface what breaks, when, and where in the code. Hand the fix to a coding agent.

Status: built and green. The knowledge base, both engines, the CLI, the Spring Boot app, the web site, and the relay all exist and pass their tests.
Start with `docs/00-vision.md`, or jump to `docs/06-testing-guide.md` to run it.

## Layout

```
docs/          vision, architecture, schemas, contributor guide, test plan, hosting
docs/adr/      architecture decision records
knowledge/     10 providers, 77 change records, 33 fixtures, detector tables
engine/        Java 25 library: detectors, inventory, matcher (no Spring)
cli/           picocli wrapper, fat jar and GraalVM native binary
app/           Spring Boot 4: webhooks, API, scan worker
web/           Nuxt: public site, browser engine, dashboard
relay/         Cloudflare Worker that relays GitHub tarballs
```

## Reading order

**New here?** Read `docs/07-getting-started.md`. It gets you from a fresh clone to a real scan.

| Document | What it covers |
|---|---|
| `docs/00-vision.md` | The problem, the product, who buys it, what we do not build |
| `docs/07-getting-started.md` | Prerequisites, first build, first scan, troubleshooting |
| `docs/08-features.md` | Every feature, how to use it, how it works |
| `docs/09-status.md` | What is built, what is pending, known issues |
| `docs/10-reference.md` | CLI flags, REST endpoints, environment variables, scripts |
| `docs/01-architecture.md` | The pieces, the seam, the surfaces, the data model |
| `docs/02-schemas.md` | Inventory, change record, detector table, fixture, finding |
| `docs/03-knowledge-base-guide.md` | How to add a provider, a rule, a deprecation |
| `docs/04-test-plan.md` | What is tested, how, and how often |
| `docs/05-hosting-and-cost.md` | What runs where and what it costs |
| `docs/06-testing-guide.md` | Four ways to test, and verified test repositories |
| `docs/adr/0001` | Java 25, Spring Boot 4, Maven, GraalVM |
| `docs/adr/0002` | Detectors as data, two interpreters, parity |
| `docs/adr/0003` | Browser scanning and the tarball relay |

## Deployment

Deployment and operations live in a separate private repository, because they
describe a specific server. This repository carries the product only.

## Status

Built and green: the knowledge base, both engines, the CLI, the server app, the web site, and the relay all exist and pass their tests. 174 engine tests plus the CLI and app suites, TypeScript unit tests, browser tests and relay tests all pass, and both engines agree byte for byte on all 33 fixtures.

Not yet done: nothing is deployed, only four of the ten launch providers exist, change records are hand-written with no ingestion automation, and the fix loop has never run against a real repository. Full list in `docs/09-status.md`.
