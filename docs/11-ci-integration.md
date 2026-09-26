# Running DocsWatcher in your CI

Catch an API shutdown when the commit lands, not when the endpoint returns 410.

DocsWatcher scans a checkout and exits non-zero when a breaking deprecation affects code you
actually call. The scan is offline: the knowledge base is compiled into the binary, so no
request leaves your runner and no service needs to be reachable.

---

> **v0.2.1 and earlier never failed a build.** Their native binary printed every finding as `{}`,
> so the Action counted zero breaking findings. v0.3.0 fixed it and `@v0` points at the latest release; a
> `version:` pinned to `v0.2.1` or earlier should be removed or set to `v0.4.0`. The Action now
> stops with an error on a report whose findings carry no severity rather than passing it. If your
> pipeline ran an affected release, run it again once. Details in `CHANGELOG.md`.

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
| `knowledge` | empty | Directories of your own API records, one per line, such as a checkout of your organisation's `.docswatcher` repository. The scanned path's own `.docswatcher/` is read without this. See [Your own APIs](#your-own-apis). |
| `report` | *(unset)* | Write the full JSON findings to this path, for a later step to upload or post. |
| `version` | `latest` | Release tag of the CLI to download, for example `v0.4.0`. |

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

A scan of `path` reads `path/.docswatcher/`. If your own API records sit at the repository root,
pass them with `knowledge: .docswatcher`.

## Your own APIs

Deprecations of your internal services fail the build the same way, once they are written down as
records ([`19-your-own-apis.md`](./19-your-own-apis.md)). Records in the repository's own
`.docswatcher/` are read with no configuration. To read your organisation's shared records, check
its `.docswatcher` repository out beside the code:

```yaml
      - uses: actions/checkout@v4
      - uses: actions/checkout@v4
        with:
          repository: acme/.docswatcher
          path: org-records
          token: ${{ secrets.DOCSWATCHER_RECORDS_TOKEN }}   # read access, if it is private
      - uses: jameskomo/docswatcher@v0
        with:
          knowledge: org-records/.docswatcher
```

Invalid records fail the step, and the log lists every error. Check them before they are merged
with `docswatcher validate .docswatcher` in the records repository's own workflow.

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

## GitLab CI

Include the template from a release tag in `.gitlab-ci.yml`:

```yaml
include:
  - remote: https://raw.githubusercontent.com/jameskomo/docswatcher/v0.4.0/ci/gitlab/docswatcher.gitlab-ci.yml
```

Pin a release tag, as above. v0.4.0 is the first release that has the
template. A tag never changes, so your pipeline changes only when you move the tag.

This adds one job, `docswatcher`, to the `test` stage. It does what the Action does:

- Downloads `docswatcher-linux-x64` from the release and checks its sha256 against that release's
  `checksums.txt`. The file becomes the binary only when `checksums.txt` lists exactly one sha256
  for it and the bytes match. Any other result deletes it and fails the job before it runs.
- Scans the checkout once. The text summary goes to the job log and the JSON findings to
  `docswatcher.json`, which is kept as an artifact.
- Fails the job when a **breaking** finding is open. When the report is missing or cannot be
  read, the job exits 3. It never passes a repository it did not check.
- Writes `gl-code-quality-report.json` as a [Code Quality](https://docs.gitlab.com/ci/testing/code_quality/)
  report. Each place a finding was seen becomes one entry with its file and line, so the merge
  request widget shows what the change introduced and what it fixed. Severity maps
  breaking → critical, warning → major, info → info. The fingerprint is built from the code, not
  the line number, so moving a line does not make a finding look new.

### Variables

Override them in your own top-level `variables:`, or on the job:

```yaml
include:
  - remote: https://raw.githubusercontent.com/jameskomo/docswatcher/v0.4.0/ci/gitlab/docswatcher.gitlab-ci.yml

docswatcher:
  variables:
    DOCSWATCHER_PATH: services/checkout
    DOCSWATCHER_FAIL_ON: never
    DOCSWATCHER_EXCLUDE: |
      samples/
      test-data/
```

| Variable | Default | What it does |
|---|---|---|
| `DOCSWATCHER_PATH` | `.` | Directory to scan. Point it at a subdirectory in a monorepo. Code Quality paths stay relative to the repository root. |
| `DOCSWATCHER_FAIL_ON` | `breaking` | `breaking` fails on a shutdown that already has a date. `never` reports without failing. |
| `DOCSWATCHER_EXCLUDE` | empty | Paths to skip, one `.gitignore`-style pattern per line. `.gitignore` files and `.docswatcherignore` already apply. |
| `DOCSWATCHER_INCLUDE_LOW` | `false` | Also report contracts found only in documentation or test files. |
| `DOCSWATCHER_VERSION` | `latest` | Release tag of the CLI, for example `v0.4.0`. Set it to the tag you include, to keep both in step. `latest` resolves to a tag once, so the binary and `checksums.txt` always come from the same release. |
| `DOCSWATCHER_IMAGE` | `python:3.13-slim` | The job's image. It needs glibc and `python3`, which reads the report. Alpine (musl) cannot run the binary. |
| `DOCSWATCHER_RELEASES` | this repository's releases | Base URL of the release downloads. Change it only for a mirror that has the same layout, including `checksums.txt`. |
| `DOCSWATCHER_DISABLED` | unset | Set to any value to skip the job. |

Any other key of the job can be overridden the same way. For example, set `stage:` when your
pipeline has no `test` stage. The job needs a Linux x64 runner, which is GitLab.com's default. On
an arm64 runner it stops and says so. Use the jar below there.

---

## Other CI systems

The Action and the GitLab template are thin wrappers around one command. Anywhere else, download
the binary and run it. The Linux binary links against glibc, so it does not run on Alpine.

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
docswatcher match <path> [--format json|text] [--report <file>] [--include-low] [--exclude <pattern>]... [--repo owner/name]
```

**Exit codes.** `0` when nothing breaking is open. `1` when at least one breaking finding is
open — that is the line that fails your build. Any other value is the tool itself failing, and
should be treated as a broken step rather than a finding.

`--format text` is meant for a human reading a build log. `--format json` is the default and is
what to parse: a top-level array of findings, each carrying `severity`, `effective`,
`daysRemaining`, `contract`, `change`, `status`, and an `evidence` array of
`{path, line, column, snippet, detector, layer}`.

To keep both, scan once: `--format text --report findings.json` prints the text and writes the
JSON beside it. Running `match` twice, once per format, scans the repository twice.

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
