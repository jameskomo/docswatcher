# DocsWatcher

Dependabot for the APIs you call, not the packages you install.

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

## Who pays, and a second buyer

Platform teams pay per organisation for the dashboard, blast radius, and the GitHub App.

Providers are the second buyer. Stripe has kept the Charges API alive for years because customers will not migrate. Every provider running a deprecation wants it to land and has budget for migration success. A provider-endorsed migration tool is distribution we do not have to pay for.

## Principles

- **Precision over recall.** A false positive wastes a developer's afternoon and they uninstall. A missed contract is invisible. Ship nothing that fires wrongly.
- **The knowledge base is the moat, so it is open.** Detectors, change records, and fixtures are public and community-editable. The hosted matcher, dashboard, and organisation features are the business.
- **Everything is data.** Detection rules are YAML, not code. Two thin interpreters, one in Java and one in TypeScript, run the same rules and are tested to agree byte for byte.
- **Zero cost until revenue.** Static hosting, browser-side compute, free tiers, and the customer's own key for fix PRs.
- **GitHub is the workflow; the dashboard is the picture.** Findings become issues with due dates and PRs, because that is where developers act. The map and the horizon live in the app, because that is where leads decide.
- **Documentation first.** Every component is specified in this folder before it is built.

## What we deliberately do not build in v1

- The runtime layer. Deprecation and Sunset headers observed from traffic come later as an OpenTelemetry processor.
- Email digests and Slack cards.
- GitLab and Bitbucket.
- Our own coding agent. Fix PRs run through a Claude Code GitHub Action in the customer's own CI with their own key.

## Validation before launch

Label fifty public repositories that reference retired model IDs or sunset endpoints. Run the scanner. Open an issue on each hit with the finding. Count who responds and who merges. The same fifty repos become the benchmark corpus, so the experiment is not throwaway work.

## Documents

| Document | What it fixes |
|---|---|
| `01-architecture.md` | The pieces, the seam, the surfaces, the data model |
| `02-schemas.md` | Inventory, change record, detector table, fixture, finding |
| `03-knowledge-base-guide.md` | How to add a provider, a rule, a deprecation, a fixture |
| `04-test-plan.md` | What is tested, how, and how often |
| `05-hosting-and-cost.md` | What runs where and what it costs |
| `06-testing-guide.md` | Four ways to test it, and verified test repositories |
| `07-getting-started.md` | From a fresh clone to a real scan |
| `08-features.md` | Every feature, how to use it, how it works |
| `09-status.md` | What is built, what is pending, known issues |
| `10-reference.md` | CLI, REST API, environment variables, scripts |
| `adr/0001-engine-language.md` | Java 25, Spring Boot 4, Maven, GraalVM |
| `adr/0002-data-driven-detectors.md` | Rules are data, two interpreters, parity |
| `adr/0003-browser-scanning-and-repo-fetch.md` | Client-side scans and the tarball relay |
