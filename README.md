# DocsWatcher

> **You can pin a package. You can't pin someone else's API.**

[Try it](https://docswatcher.vukisha.co.ke) ·
[Deprecation calendar](https://docswatcher.vukisha.co.ke/#/calendar) ·
[Add it to CI](./docs/11-ci-integration.md) ·
Apache-2.0

> **Using the GitHub Action or the native binary from v0.2.1 or earlier? Upgrade to v0.4.0.**
> Those releases reported every finding as empty, so the Action never failed a build. `@v0` now
> points at the fix. Re-run it once: see [the changelog](./CHANGELOG.md).

Stripe retires an API version. OpenAI shuts down a model. Shopify drops a release a year after
publishing it. Your lockfile cannot help with any of them: the code still compiles, the tests
still pass against their mocks, and one morning a payment fails.

DocsWatcher reads a repository, finds every external API it actually calls, and tells you which
ones already have a shutdown date — with the file, the line, and the date.

It currently tracks **309 published shutdowns and changes across 27 providers**. 144 of those are AI model
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
Call sites are parsed in Java, Python, TypeScript, JavaScript, Go, Ruby, PHP and C#; manifests are
read from `package.json`, `requirements*.txt`, `pyproject.toml`, `pom.xml`, `go.mod`, `Gemfile`,
`composer.json` and `*.csproj`.

## Four ways to run it

**In your browser.** Paste a public GitHub repository or GitLab project URL, or pick a local folder.
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

That is Linux x64. On a Mac with Apple silicon, download `docswatcher-macos-arm64` instead; on
Windows, `docswatcher-windows-x64.exe`. Anywhere else with Java 25, use `docswatcher.jar`. Every
release lists the sha256 of each file in `checksums.txt`, and the Action checks it before running.

On GitLab, include the template from a release tag. The job checks the binary the same way, and
shows findings in the merge request's Code Quality widget:

```yaml
include:
  - remote: https://raw.githubusercontent.com/jameskomo/docswatcher/v0.4.0/ci/gitlab/docswatcher.gitlab-ci.yml
```

See [`docs/11-ci-integration.md`](./docs/11-ci-integration.md) for the GitLab variables, Jenkins,
monorepos, and how to introduce it to a codebase that already has findings without blocking your
team.

**In your coding agent.** Your agent's training data is older than the deprecation list, so it
writes model IDs that are already scheduled to die. `docswatcher mcp` runs the same binary as an
[MCP](https://modelcontextprotocol.io) server, and the agent checks an identifier before it
writes one:

```bash
claude mcp add docswatcher -- docswatcher mcp      # Claude Code; Cursor and others take an mcpServers block
```

Asked for "a script using gpt-4-turbo", Claude Code called `check_api` on its own and warned that
the model shuts down on 2026-10-23, naming the replacement. Offline, read-only, nothing leaves the
machine. See [`docs/14-coding-agents.md`](./docs/14-coding-agents.md).

**Watching a repository.** The GitHub App, or a connected GitLab group, scans on every push to the
default branch and opens an issue per finding with the file and line, with a check run on GitHub
and a commit status on GitLab. On GitHub a label can also open a fix pull request. Sign in with
GitHub or GitLab on the [dashboard](https://docswatcher.vukisha.co.ke/#/app) to see every
repository's findings in one view, the blast radius of one shutdown, email and Slack alerts before
the date, and, from your own OpenTelemetry, which deprecated calls production actually makes
([`docs/13-runtime-observation.md`](./docs/13-runtime-observation.md)). Setup is in
[`docs/07-getting-started.md`](./docs/07-getting-started.md); the
[Teams page](https://docswatcher.vukisha.co.ke/#/teams) says how to start.

## Keeping test data out

Anything your `.gitignore` excludes is never scanned. For committed files that are not your
product, such as fixtures or sample configs, add a `.docswatcherignore` at the root in the same
syntax. See [`docs/18-excluding-paths.md`](./docs/18-excluding-paths.md).

## Your own APIs too

Your internal services deprecate things as well. Write them down in the same format, in a
`.docswatcher/` directory, and every repository that calls them gets the same findings, in CI, in
the browser, from the App and in the coding agent. Keep them in your organisation's `.docswatcher`
repository and nobody copies a file. See [`docs/19-your-own-apis.md`](./docs/19-your-own-apis.md).

## What it will not tell you

It only knows about deprecations someone has written down. **A green result means nothing
*known* is expiring — not that nothing is.**

The knowledge base is plain YAML in this repository and takes pull requests. If a provider you
depend on is missing, adding it is a detector rule and a change record:
[`docs/03-knowledge-base-guide.md`](./docs/03-knowledge-base-guide.md).

It also will not rewrite your code for you. It can hand a coding agent the exact locations and
the provider's migration notes, and the agent opens a pull request you review like any other.

## Providers tracked today

OpenAI · Anthropic · Google AI · Azure OpenAI · Mistral AI · Cohere · Shopify · Stripe · PayPal · Square ·
Paystack · Meta Graph API · X (Twitter) API · LinkedIn Marketing API · YouTube Data API · Discord ·
Twitch · Google Maps Platform · Firebase · Salesforce · HubSpot · Mailchimp · AWS SDK · GitHub · Slack ·
SendGrid · Twilio

The [deprecation calendar](https://docswatcher.vukisha.co.ke/#/calendar) shows every tracked
shutdown on a timeline, whether or not you have scanned anything. Subscribe to it in Google
Calendar, Apple Calendar or Outlook ([`deprecations.ics`](https://docswatcher.vukisha.co.ke/feeds/deprecations.ics),
or one provider at `/feeds/openai.ics`), follow it as an
[Atom feed](https://docswatcher.vukisha.co.ke/feeds/deprecations.atom), or build on the
[open JSON](https://docswatcher.vukisha.co.ke/feeds/deprecations.json). Or ask for an email 30 and
7 days before each date, confirmed from your inbox first, with a one-click unsubscribe.

A scan of a public repository has a link that re-runs it for whoever opens it,
`https://docswatcher.vukisha.co.ke/#/?repo=owner/name`, and a README badge that does the same.

The [Agents page](https://docswatcher.vukisha.co.ke/#/agents) lets you try it with your own
assistant: paste a Claude, OpenAI or Gemini key and see the same model's answer with and without
DocsWatcher. The key goes from your browser to the provider and nowhere else.

A [scheduled job](./docs/16-knowledge-watch.md) re-reads every page the knowledge base cites each
day and opens an issue when one announces something new, so the records do not quietly go stale.

## How it is put together

Four pieces: a browser scanner (`web/`), a CLI (`cli/`), a server app (`app/`) behind the GitHub
App, GitLab, the dashboard and alerts, and the open
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

# the tests: 354 Java, 132 TypeScript, 30 end-to-end
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
| [`docs/14-coding-agents.md`](./docs/14-coding-agents.md) | The MCP server for Claude Code, Cursor and other agents |
| [`docs/15-feeds-and-sharing.md`](./docs/15-feeds-and-sharing.md) | Calendar, feed, open data, live scan links and badges |
| [`docs/16-knowledge-watch.md`](./docs/16-knowledge-watch.md) | Keeping the knowledge base true as provider pages change |
| [`docs/17-open-source-study.md`](./docs/17-open-source-study.md) | Scanning public repositories, and publishing what we find |
| [`docs/18-excluding-paths.md`](./docs/18-excluding-paths.md) | `.gitignore`, `.docswatcherignore` and `--exclude` |
| [`docs/19-your-own-apis.md`](./docs/19-your-own-apis.md) | Your internal services' deprecations, found the same way |

`deployment/` is intentionally not in this repository: it describes one specific server, its
secret layout and its tunnel, so publishing it would document an attack surface without helping
anyone run their own.

## License

[Apache License 2.0](LICENSE).
