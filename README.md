# DocWatcher

Dependabot for the APIs you call, not the packages you install.

Scan a repository for every external contract it depends on. Match the inventory against an open knowledge base of provider deprecations. Surface what breaks, when, and where in the code. Hand the fix to a coding agent.

Status: built and green. The knowledge base, both engines, the CLI, the Spring Boot app, the web site, and the relay all exist and pass their tests.
Start with `docs/00-vision.md`, or jump to `docs/06-testing-guide.md` to run it.

## Layout

```
docs/          vision, architecture, schemas, contributor guide, test plan, hosting
docs/adr/      architecture decision records
knowledge/     4 providers, 50 change records, 14 fixtures, detector tables
engine/        Java 25 library: detectors, inventory, matcher (no Spring)
cli/           picocli wrapper, fat jar and GraalVM native binary
app/           Spring Boot 4: webhooks, API, scan worker
web/           Nuxt: public site, browser engine, dashboard
relay/         Cloudflare Worker that relays GitHub tarballs
```

## Reading order

1. `docs/00-vision.md`
2. `docs/adr/` in number order
3. `docs/01-architecture.md`
4. `docs/02-schemas.md`
5. `docs/03-knowledge-base-guide.md`
6. `docs/04-test-plan.md`
7. `docs/05-hosting-and-cost.md`
8. `docs/06-testing-guide.md`
