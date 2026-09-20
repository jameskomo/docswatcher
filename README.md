# DocsWatcher

> **You can pin a package. You can't pin someone else's API.**

[Try it](https://docswatcher.vukisha.co.ke) ·
[Deprecation calendar](https://docswatcher.vukisha.co.ke/#/calendar) ·
[Add it to CI](./docs/11-ci-integration.md) ·
Apache-2.0

Stripe retires an API version. OpenAI shuts down a model. Shopify drops a release a year after
publishing it. Your lockfile cannot help with any of them: the code still compiles, the tests
still pass against their mocks, and one morning a payment fails.

DocsWatcher reads a repository, finds every external API it actually calls, and tells you which
ones already have a shutdown date — with the file, the line, and the date.

It currently tracks **77 published shutdowns across 10 providers**. 52 of those are AI model
retirements, which is where this hurts most right now.

## What a scan finds

Four kinds of thing, because an API dependency hides in four places:

| | Example |
|---|---|
| SDK packages and versions | `openai==1.0.0` in `requirements.txt` |
| Endpoint paths and base URLs | `https://api.stripe.com/v1/sources` |
| Model IDs in configuration | `model: dall-e-2` in a YAML file |
| API versions pinned in headers or paths | `Stripe-Version: 2022-11-15`, `/admin/api/2024-04/` |

It finds them by parsing, not grepping: a manifest pass to confirm the SDK is really installed,
a literal pass for versions and model strings, then tree-sitter queries over the syntax tree for
the actual call sites. That is why it can point at a line and a column instead of a file.

## Three ways to run it

**In your browser.** Paste a public repository URL, or pick a local folder.
[docswatcher.vukisha.co.ke](https://docswatcher.vukisha.co.ke). Parsing happens client-side in
WebAssembly, so no file contents are transmitted. The most recent scan is kept in your browser's
local storage so a reload does not lose it, and there is a control to clear it.

**In your CI.** Five lines, and the build fails when a breaking deprecation is open:

```yaml
- uses: actions/checkout@v4
- uses: jameskomo/docswatcher@v0
```

Or one command anywhere with a shell:

```bash
curl -sSL -o docswatcher \
  https://github.com/jameskomo/docswatcher/releases/latest/download/docswatcher-linux-x64
chmod +x docswatcher
./docswatcher match . --format text     # exits 1 if anything breaking is open
```

See [`docs/11-ci-integration.md`](./docs/11-ci-integration.md) for GitLab, Jenkins, monorepos,
and how to introduce it to a codebase that already has findings without blocking your team.

**Watching a repository.** The GitHub App scans on every push, opens an issue per finding with
the file and line, and can open a fix pull request when you add a label. Setup is in
[`docs/07-getting-started.md`](./docs/07-getting-started.md).

## What it will not tell you

It only knows about deprecations someone has written down. **A green result means nothing
*known* is expiring — not that nothing is.**

The knowledge base is plain YAML in this repository and takes pull requests. If a provider you
depend on is missing, adding it is a detector rule and a change record:
[`docs/03-knowledge-base-guide.md`](./docs/03-knowledge-base-guide.md).

It also will not rewrite your code for you. It can hand a coding agent the exact locations and
the provider's migration notes, and the agent opens a pull request you review like any other.

## Providers tracked today

OpenAI · Anthropic · Google AI · Shopify · Stripe · AWS SDK · GitHub · Slack · SendGrid · Twilio

The [deprecation calendar](https://docswatcher.vukisha.co.ke/#/calendar) shows every tracked
shutdown on a timeline, whether or not you have scanned anything.

## How it is put together

Four pieces: a browser scanner (`web/`), a CLI (`cli/`), a GitHub App (`app/`), and the open
knowledge base (`knowledge/`). The detection rules are declarative YAML shared by two
independent engines — one in Java, one in TypeScript — and a parity check runs both over every
fixture on each build and fails if their output differs by a byte. A finding you get in the
browser is the same finding CI would give you.

Architecture and the reasoning behind it:
[`docs/01-architecture.md`](./docs/01-architecture.md) and the ADRs in [`docs/adr/`](./docs/adr).

## Building it yourself

Needs **Java 25** and **Node 20+**.

```bash
# the web scanner
cd web && npm install && npm run dev

# the CLI
./mvnw -pl cli -am package -DskipTests
java -jar cli/target/docswatcher-cli.jar match /path/to/project --format text

# the tests: 174 Java, 48 TypeScript, 14 end-to-end
./mvnw test
cd web && npm test && npm run e2e
```

## Documentation

| | |
|---|---|
| [`docs/00-vision.md`](./docs/00-vision.md) | What this is for and who it is for |
| [`docs/01-architecture.md`](./docs/01-architecture.md) | Module design, engine seams, data models |
| [`docs/02-schemas.md`](./docs/02-schemas.md) | Inventory, change record and finding schemas |
| [`docs/03-knowledge-base-guide.md`](./docs/03-knowledge-base-guide.md) | Adding a provider, writing detector rules |
| [`docs/04-test-plan.md`](./docs/04-test-plan.md) | Parity testing, negative fixtures, benchmarks |
| [`docs/05-deployment.md`](./docs/05-deployment.md) | Topology, containers, ingress |
| [`docs/06-testing-guide.md`](./docs/06-testing-guide.md) | Recipes and public benchmark repositories |
| [`docs/07-getting-started.md`](./docs/07-getting-started.md) | Clone to first scan, and GitHub App setup |
| [`docs/08-features.md`](./docs/08-features.md) | Capabilities and detection mechanics in full |
| [`docs/09-status.md`](./docs/09-status.md) | What works, what does not exist yet |
| [`docs/10-reference.md`](./docs/10-reference.md) | CLI commands, REST endpoints, configuration |
| [`docs/11-ci-integration.md`](./docs/11-ci-integration.md) | Running it in a pipeline |
| [`docs/13-runtime-observation.md`](./docs/13-runtime-observation.md) | Feeding it live traffic to prioritise findings |

`deployment/` is intentionally not in this repository: it describes one specific server, its
secret layout and its tunnel, so publishing it would document an attack surface without helping
anyone run their own.

## License

[Apache License 2.0](LICENSE).
