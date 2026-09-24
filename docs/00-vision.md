# DocsWatcher

You can pin a package. You can't pin someone else's API.

## The problem

Every codebase depends on external contracts nobody tracks: a Stripe endpoint, a model ID in a config file, a Shopify API version in a URL, a webhook payload shape. Providers change these on a schedule. Stripe sunsets an endpoint. OpenAI retires a model. Shopify removes an API version twelve months after release. Twilio changes a field type and mentions it in a changelog.

Dependabot bumps package versions. It does not know that the endpoint your checkout calls stops working on November 30. Nothing in the toolchain does.

The people who feel this are platform and infrastructure teams at companies with many services, where nobody knows what calls what. They find out when a batch job fails, or when a customer cannot pay.

## What exists

Watching changelogs is a solved, crowded, cheap problem. Page monitors summarise API docs with AI. Apify actors diff OpenAPI specs. Several products offer continuous monitoring of third-party APIs. An open-source project on PyPI has the same pitch as ours, but asks you to declare the APIs you use by hand. Optic, the closest prior art, was archived in January 2026.

Every one of these produces a feed. None of them knows your code.

## The product

Not "an API changed." Instead: "these three files in your repo call the endpoint being removed on November 30, here is the pull request."

Three parts:

1. **Discovery from code, not from a form.** Scan the repo. SDK packages in manifests, model IDs and version pins in config, endpoint literals in source, SDK calls resolved through the syntax tree. The output is a living inventory of every external contract the codebase depends on, including the ones nobody remembers adding.
2. **Sources fused, then matched to the inventory.** Provider changelogs, OpenAPI diffs, forwarded deprecation emails, and Deprecation and Sunset response headers seen at runtime. A change becomes an alert only when it hits something in the inventory. That is the whole difference between a feed and a product.
3. **Remediation.** Each finding can open a pull request with the migration guide as context and the affected tests run. Coding agents made this cheap. We do not build the agent; we build the trigger and the context.

## Why now

Two things changed the base rate of breaking API changes.

- AI model retirements happen every few months across every major provider, and every company now has model IDs in config. This is the highest-frequency deprecation event in most codebases.
- Coding agents can perform a migration when told exactly what to change and why. What they cannot do is know when to start. That knowledge is the product.

## What makes it spectacular

- **Scan any public repo in the browser, instantly.** Paste a URL. The scan runs client-side. No install, no signup, no code leaves the browser. This is the first thirty seconds of every user's experience and the demo in every conversation.
- **The map.** Every external service the codebase depends on, coloured by health, sized by call sites. Most engineering leads have never seen this picture of their own system.
- **The horizon.** A twelve-month timeline of what breaks when, with affected repos and teams under each date.
- **Blast radius.** One deprecation, every repo and team it touches, one button to open fix PRs across all of them. This is what an organisation with two hundred services pays for.
- **A public deprecation calendar.** Every upcoming sunset across every tracked provider, generated from the open-source knowledge base. Each entry ends with "check whether your repo is affected."

## Principles

- **Precision over recall.** A false positive wastes a developer's afternoon and causes churn. A missed contract is invisible. We ship nothing that fires wrongly.
- **The knowledge base is open.** Detectors, change records, and fixtures are public, community-editable, and verified with reproducible test fixtures.
- **Everything is data.** Detection rules are declarative YAML, not hardcoded logic. Two thin interpreters (Java and TypeScript) execute the same rules and are tested to produce byte-identical results.
- **Client-side & privacy-first compute.** Static hosting and browser-side WebAssembly AST parsing mean zero proprietary code leaves the developer's machine during scans.
- **GitHub is the workflow; the dashboard is the picture.** Findings become issues with due dates and actionable remediation PRs. The dependency map and horizon live in the dashboard.
- **Documentation first.** Every component is specified and documented before implementation.

## What we deliberately do not build in v1

- The runtime traffic layer. Passively observing Deprecation and Sunset headers from production traffic arrives in v2 as an OpenTelemetry processor.
- Email digests and Slack notifications.
- GitLab and Bitbucket server integrations (focused on GitHub first). GitLab CI and scanning a GitLab project in the browser need no server and already work.
- Proprietary coding agent. Remediation integrates cleanly with coding agents (like Claude Code, GitHub Copilot, or Cursor) in the repository's own CI environment.

## Verification & Corpus

The scanner is continuously validated against real public repositories (e.g. OpenAI Quickstarts, Shopify templates, Stripe samples) to ensure real-world precision and zero false-positive detection on negative fixtures.

## Documents

| Document | What it covers |
|---|---|
| `01-architecture.md` | The pieces, the seam, the surfaces, the data model |
| `02-schemas.md` | Inventory, change record, detector table, fixture, finding |
| `03-knowledge-base-guide.md` | How to add a provider, a rule, a deprecation, a fixture |
| `04-test-plan.md` | What is tested, how, and how often |
| `05-deployment.md` | Deployment architecture and container topology |
| `06-testing-guide.md` | Four ways to test it, and verified test repositories |
| `07-getting-started.md` | From a fresh clone to a real scan |
| `08-features.md` | Every feature, how to use it, how it works |
| `09-status.md` | Architecture status, test coverage, and roadmap |
| `10-reference.md` | CLI, REST API, environment variables, scripts |
| `adr/0001-engine-language.md` | Java 25, Spring Boot 4, Maven, GraalVM |
| `adr/0002-data-driven-detectors.md` | Rules are data, two interpreters, parity |
| `adr/0003-browser-scanning-and-repo-fetch.md` | Client-side scans and the tarball relay |
