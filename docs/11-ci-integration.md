# Running DocsWatcher in your CI

Catch an API shutdown when the commit lands, not when the endpoint returns 410.

DocsWatcher scans a checkout and exits non-zero when a breaking deprecation affects code you
actually call. The scan is offline: the knowledge base is compiled into the binary, so no
request leaves your runner and no service needs to be reachable.

---

## The fastest path: the GitHub Action

```yaml
# .github/workflows/api-contracts.yml
name: API contracts
on: [push, pull_request]

jobs:
  docswatcher:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: jameskomo/docswatcher@v0
```

That is the whole integration. The step fails the build if any **breaking** finding is open,
writes a summary to the job page, and says nothing at all when your code calls nothing that is
going away.

It runs on Linux x64 (`ubuntu-latest`), macOS arm64 (`macos-latest`) and Windows x64
(`windows-latest`) runners. The step downloads the DocsWatcher binary for the runner from the
release, checks its sha256 against that release's `checksums.txt`, and fails without running it
if the two do not match.

### Inputs

| Input | Default | What it does |
|---|---|---|
| `path` | `.` | Directory to scan. Point it at a subdirectory in a monorepo. |
| `fail-on` | `breaking` | `breaking` fails on a shutdown that already has a date. `never` reports without failing, which is how to introduce this to an existing codebase. |
| `include-low` | `false` | Also report contracts found only in documentation or test files. Off by default because those are usually noise. |
| `exclude` | empty | Paths to skip, one `.gitignore`-style pattern per line. The repository's `.gitignore` files and `.docswatcherignore` already apply without this. |
| `report` | *(unset)* | Write the full JSON findings to this path, for a later step to upload or post. |
| `version` | `latest` | Release tag of the CLI to download, for example `v0.2.1`. |

### Outputs

| Output | |
|---|---|
| `findings` | Total findings |
| `breaking` | Findings with a shutdown date that has passed or is scheduled |
| `report` | Path to the JSON report, when `report` was set |

---

## Introducing it to a codebase that already has findings

Turning this on for the first time on a mature service usually surfaces something. Fail the
build from day one and you have blocked your team on work they did not plan. Start in report
mode:

```yaml
      - uses: jameskomo/docswatcher@v0
        with:
          fail-on: never
```

Read the summary for a week, fix or snooze what it found, then drop the `fail-on` line. From
then on a new breaking dependency cannot reach your default branch.

## Keeping fixtures and sample data out

If the scan flags files that are not your product (fixtures, sample configs, a vendored catalog of
model names), list them in a `.docswatcherignore` at the repository root, in `.gitignore` syntax:

```
# Recorded API responses for the tests
/test-data/
samples/
```

Every `.gitignore` in the repository applies too, so build output is never scanned. For one
workflow only, use the `exclude` input. Details: [`18-excluding-paths.md`](./18-excluding-paths.md).

## Scanning one service in a monorepo

```yaml
      - uses: jameskomo/docswatcher@v0
        with:
          path: services/checkout
```

Run the step once per service if you want a separate pass or fail per service. Scanning the
repository root works too, and reports every service at once.

## Posting the findings on the pull request

```yaml
      - uses: jameskomo/docswatcher@v0
        id: scan
        with:
          fail-on: never
          report: docswatcher.json

      - if: steps.scan.outputs.breaking != '0'
        uses: actions/github-script@v7
        with:
          script: |
            // The report is a top-level array of findings.
            const findings = JSON.parse(require('fs').readFileSync('docswatcher.json', 'utf8'));
            const lines = findings
              .filter(f => f.severity === 'breaking')
              .map(f => `- \`${f.contract}\` stops working ${f.effective ?? 'soon'} — ${f.evidence[0].path}:${f.evidence[0].line}`);
            await github.rest.issues.createComment({
              ...context.repo,
              issue_number: context.issue.number,
              body: `**DocsWatcher found ${lines.length} breaking API deprecations**\n\n${lines.join('\n')}`,
            });
```

---

## Other CI systems

The Action is a thin wrapper around one command. Anywhere else, download the binary and run it.

### GitLab CI

```yaml
docswatcher:
  image: alpine:3
  before_script:
    - apk add --no-cache curl
    - curl -sSL -o /usr/local/bin/docswatcher https://github.com/jameskomo/docswatcher/releases/latest/download/docswatcher-linux-x64
    - chmod +x /usr/local/bin/docswatcher
  script:
    - docswatcher match . --format text
```

### Jenkins, CircleCI, anything with a shell

```bash
curl -sSL -o docswatcher \
  https://github.com/jameskomo/docswatcher/releases/latest/download/docswatcher-linux-x64
chmod +x docswatcher
./docswatcher match . --format text
```

On a macOS arm64 machine the file is `docswatcher-macos-arm64`, and on Windows x64
`docswatcher-windows-x64.exe`. There is no native build for Intel Macs; use the jar below.

To check a download against the release before running it, keep the published file name and
let `sha256sum` (`shasum -a 256` on macOS) compare it with `checksums.txt`:

```bash
base=https://github.com/jameskomo/docswatcher/releases/latest/download
curl -sSLO "$base/docswatcher-linux-x64"
curl -sSLO "$base/checksums.txt"
sha256sum --check --ignore-missing checksums.txt
```

### On a JVM, with no native binary

Needs **Java 25 or newer** — the native binary above needs no JVM at all and is the easier
choice on a runner you do not control.

```bash
curl -sSL -o docswatcher.jar \
  https://github.com/jameskomo/docswatcher/releases/latest/download/docswatcher.jar
java --enable-native-access=ALL-UNNAMED -jar docswatcher.jar match . --format text
```

The `--enable-native-access` flag only silences a JVM warning about the tree-sitter parser
loading its native library; the scan works without it.

---

## The command underneath

```
docswatcher match <path> [--format json|text] [--include-low] [--exclude <pattern>]... [--repo owner/name]
```

**Exit codes.** `0` when nothing breaking is open. `1` when at least one breaking finding is
open — that is the line that fails your build. Any other value is the tool itself failing, and
should be treated as a broken step rather than a finding.

`--format text` is meant for a human reading a build log. `--format json` is the default and is
what to parse: a top-level array of findings, each carrying `severity`, `effective`,
`daysRemaining`, `contract`, `change`, `status`, and an `evidence` array of
`{path, line, column, snippet, detector, layer}`.

## What it will and will not catch

It finds contracts your code **calls**: SDK methods, base URLs and endpoint paths, AI model
identifiers in configuration, and API versions pinned in headers or paths. It matches them
against an open knowledge base of published provider shutdowns.

It will not tell you about a provider whose deprecation nobody has recorded yet. The knowledge
base is public and takes pull requests — see
[`docs/03-knowledge-base-guide.md`](./03-knowledge-base-guide.md). If your build passes, it
means nothing *known* is expiring, which is not the same as nothing expiring.

## If you would rather not run it yourself

The GitHub App watches a repository continuously instead of on each build: it scans on push,
opens an issue per finding with the file and line, and can open a fix pull request when you add
a label. See [`docs/07-getting-started.md`](./07-getting-started.md). The two are complementary
— CI blocks the new mistake, the App tracks the ones already there.
