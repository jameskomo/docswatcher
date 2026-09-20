# DocsWatcher

> **You can pin a package. You can't pin someone else's API.**

[![Live Web App](https://img.shields.io/badge/Hosted%20Radar-docswatcher.vukisha.co.ke-0284c7?style=flat-square&logo=cloudflare)](https://docswatcher.vukisha.co.ke)
[![Knowledge Base](https://img.shields.io/badge/Knowledge%20Base-10%20Providers%20%7C%2077%20Rules-10b981?style=flat-square)](https://docswatcher.vukisha.co.ke/#/calendar)
[![Test Suite](https://img.shields.io/badge/Tests-100%25%20Passing-success?style=flat-square)](./docs/04-test-plan.md)
[![Privacy](https://img.shields.io/badge/Client--Side%20AST-100%25%20Private-violet?style=flat-square)](#-privacy--security-model)

Every codebase depends on external contracts nobody actively tracks: third-party payment endpoints, AI model IDs in configuration files, API version dates in URLs, and deprecating SDK methods. Providers change these on aggressive release schedules. Stripe sunsets an API version, OpenAI retires a model series, and Shopify shuts down an API release twelve months after publication.

Package managers bump dependency versions, but **nothing in the standard toolchain warns you that an endpoint your checkout service calls will cease to exist on November 30.**

**DocsWatcher closes this gap.** It scans repositories, constructs a living inventory of every external contract, matches it against an open knowledge base of provider deprecations, surfaces exact breaking dates and call-site coordinates, and generates actionable prompts for coding agents to open automated fix PRs.

---

## 🌐 Try the Live Hosted Service

DocsWatcher is deployed and accessible at:
### 👉 [**https://docswatcher.vukisha.co.ke**](https://docswatcher.vukisha.co.ke)

* **Instant In-Browser AST Scanning**: Paste any public GitHub repository URL, pick a sample repository, or select a local project directory.
* **100% Private (Zero Code Uploaded)**: All syntax parsing and rule evaluation execute client-side in your browser via WebAssembly Tree-sitter. Your code never leaves your device.
* **Interactive Time Horizon**: Visualize upcoming API deprecations on a chronological timeline with an overdue danger zone and a live countdown to every contract deadline.
* **Continuous Deprecation Calendar**: Explore upcoming sunsets across 10 major providers at [`https://docswatcher.vukisha.co.ke/#/calendar`](https://docswatcher.vukisha.co.ke/#/calendar).
* **AI Remediation Terminal**: View exact code evidence and copy pre-assembled fix prompts ready for Claude Code, GitHub Copilot, or Cursor.

---

## 🛠️ The Four Surfaces

DocsWatcher provides four distinct interfaces suited for development, continuous integration, and fleet management:

```
┌────────────────────────────────────────────────────────────────────────┐
│                              DocsWatcher                               │
├───────────────────┬────────────────────┬─────────────────┬─────────────┤
│ 1. Web Console    │ 2. CI/CD CLI       │ 3. GitHub App   │ 4. Open KB  │
│ Browser WASM AST  │ GraalVM native binary│ Spring Boot 4 │ Declarative │
│ Telemetry HUD     │ Sub-second scans   │ Webhooks & PRs  │ YAML rules  │
│ Zero code uploaded│ SARIF & JSON out   │ Auto-fix loop   │ 10 providers│
└───────────────────┴────────────────────┴─────────────────┴─────────────┘
```

1. **Web Radar Console (`web/`)**: Nuxt 3 static SPA delivering a high-precision developer telemetry HUD, dynamic dependency graphs, and client-side AST inspection.
2. **Static Analysis CLI (`cli/`)**: Picocli CLI packaged as a GraalVM native binary or executable JAR for fast pre-commit hooks and CI pipelines (`docswatcher scan --repo . --json`).
3. **Continuous GitHub App (`app/`)**: Spring Boot 4 service running on Java 25. Ingests GitHub webhooks, scans commits asynchronously, records inventory in PostgreSQL, posts Check Runs, and dispatches automated fix workflows.
4. **Open Knowledge Base (`knowledge/`)**: Open-source collection of YAML detection rules, change records, and verified test fixtures tracking deprecations across 10 major developer platforms.

---

## 🔍 How It Works: The Three-Layer Detection Engine

Rather than relying on naive text grep or unvalidated AI summaries, DocsWatcher employs a layered multi-pass engine that guarantees precision over recall:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Manifest Layer: SDK package coordinates & pinned version │
├─────────────────────────────────────────────────────────────┤
│ 2. Literal Layer: Model IDs, API dates, URI route constants │
├─────────────────────────────────────────────────────────────┤
│ 3. Call-Site Layer: Tree-sitter AST queries for methods     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ Contract Inventory & Matcher against Open Knowledge Base    │
└─────────────────────────────────────────────────────────────┘
```

* **Layer 1: Manifest Gate**: Analyzes `package.json`, `pom.xml`, `requirements.txt`, `go.mod`, and `Gemfile` to verify that an SDK is actually an installed dependency.
* **Layer 2: Config & Literals**: Detects version pins, model identifiers (e.g. `gpt-3.5-turbo-0125`, `claude-2.0`), and versioned endpoints across source code and configuration.
* **Layer 3: Syntax Tree Call Sites**: Uses Tree-sitter (via `java-tree-sitter` in Java and `web-tree-sitter` in the browser) to parse concrete syntax trees and pinpoint exact method calls with file, line, and column precision.

Both the Java and TypeScript engines share identical declarative YAML rules and are tested on every build to guarantee **byte-for-byte identical output**.

---

## 📡 Tracked Ecosystem Providers

The open knowledge base actively monitors deprecation schedules, API version shutdowns, and model retirements for:

| Provider | Tracked Contracts | Examples |
|---|---|---|
| **OpenAI** | Model shutdowns & API migrations | Assistants v1, `gpt-3.5-turbo-0125`, Completions API |
| **Anthropic** | Model deprecations & message formats | `claude-2.0`, `claude-2.1`, legacy completions |
| **Google AI** | Gemini model retirements | `gemini-1.0-pro`, legacy embedding endpoints |
| **Shopify** | Admin API quarterly version sunsets | Expired release versions (e.g. `2024-10`, `2024-07`) |
| **Stripe** | Legacy API endpoints & sources | Sources API, Tokens API, deprecated charge fields |
| **AWS SDK** | SDK v1/v2 lifecycles | Go SDK v1, Java SDK v1, Node SDK v2 retirement |
| **GitHub** | REST API versioning & sunset routes | `2022-11-28` version pins, legacy search endpoints |
| **Slack** | Web API methods & Webhooks | `files.upload` migration to `files.getUploadURLExternal` |
| **SendGrid** | Mail API deprecations | Legacy v2 Web API endpoints |
| **Twilio** | REST API changes | Legacy notification & subresource endpoints |

---

## 🔒 Privacy & Security Model

* **Zero Code Exfiltration**: During public browser scans, source files are decompressed in browser memory and parsed directly via WebAssembly. Neither file contents nor repository metadata are transmitted to external servers.
* **Secure GitHub App Execution**: The server application processes repositories in isolated ephemeral working directories with zero long-term source retention.
* **In-Repo AI Remediation**: Fix pull requests are triggered via standard `repository_dispatch` events that run inside your own GitHub Actions environment with your own secrets.

---

## 🚀 Local Development & Quickstart

### Prerequisites
* **Java 25** (for CLI, Engine, and Server App)
* **Node.js 20+** (for Web frontend and Relay worker)
* **Docker** (for local PostgreSQL and deployment testing)

### 1. Run the Web Application Locally
```bash
cd web
npm install
npm run dev
```
Open [http://localhost:3000](http://localhost:3000) in your browser.

### 2. Run the Command-Line Scanner
```bash
# Build the CLI jar
./mvnw -pl cli -am package -DskipTests

# Scan any local directory
java -jar cli/target/docswatcher-cli.jar scan --repo /path/to/project --format text
```

### 3. Run the Test Suite
```bash
# Java Engine & App tests (174 tests)
./mvnw test

# TypeScript Engine Unit Tests (48 tests)
cd web && npm test

# Playwright E2E Tests (11 tests)
npm run generate && npx playwright test tests/e2e/site.spec.ts
```

---

## 📚 Documentation Index

For in-depth guides, architectural decision records, and operational manuals:

| Guide | Description |
|---|---|
| [`docs/01-architecture.md`](./docs/01-architecture.md) | Multi-module design, engine seams, data models, and lifecycles |
| [`docs/02-schemas.md`](./docs/02-schemas.md) | Specification of inventory, change records, and findings |
| [`docs/03-knowledge-base-guide.md`](./docs/03-knowledge-base-guide.md) | How to add new providers, YAML detector rules, and test fixtures |
| [`docs/04-test-plan.md`](./docs/04-test-plan.md) | Parity testing, negative fixtures, and continuous verification |
| [`docs/05-deployment.md`](./docs/05-deployment.md) | Infrastructure topology, container layout, and ingress |
| [`docs/06-testing-guide.md`](./docs/06-testing-guide.md) | Testing recipes and verified public benchmark repositories |
| [`docs/07-getting-started.md`](./docs/07-getting-started.md) | Step-by-step onboarding from clone to first scan |
| [`docs/08-features.md`](./docs/08-features.md) | Complete inventory of capabilities and detection mechanics |
| [`docs/09-status.md`](./docs/09-status.md) | Current test coverage, verified repositories, and roadmap |
| [`docs/10-reference.md`](./docs/10-reference.md) | CLI commands, REST endpoints, and environment variables |
| `deployment/` *(private)* | Server setup, Cloudflare Tunnel configuration, and systemd ops. Kept out of this repository because it describes one specific deployment; see [`docs/05-deployment.md`](./docs/05-deployment.md) for the architecture. |

---

## 📄 License

Licensed under the [Apache License 2.0](LICENSE).
