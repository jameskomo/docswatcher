# Excluding paths

Written 2026-09-23, before the code. The test plan at the end is the definition of done.

## The problem

"It flagged my test data" is the likeliest reason someone takes DocsWatcher out of CI. Two cases:

- **Files git ignores.** Build output, bundles and generated data. Scanning DocsWatcher's own
  working tree gave 92 findings, and 696 of the evidence hits were in gitignored output
  (`web/.output`, `web/public`, `web/generated`). A CI checkout never has these files; a laptop,
  the folder picker and an agent's `scan_repository` call always do.
- **Committed files that are not the product.** Fixtures, sample data, a vendored catalog of model
  names. A clean clone of DocsWatcher gives 68 findings, and none of them rests on product code:
  they come from `knowledge/fixtures`, the knowledge base records, tests and docs. Git cannot
  ignore these, because they are meant to be committed.

Test directories and documentation are already lowered to low confidence and hidden by default.
That stays. This is for everything else.

## What ships

Three ways to exclude, all using `.gitignore` syntax:

| Source | Scope | Notes |
|---|---|---|
| Every `.gitignore` in the scanned tree | Its own directory, as git applies it | On by default. Nothing to configure |
| `.docswatcherignore` at the repository root | The whole repository | For committed paths DocsWatcher should not read |
| `--exclude <pattern>` on `scan`, `match`; `exclude` on the Action and on MCP `scan_repository` | The whole repository | For one run. Repeatable |

Later sources win: a `.gitignore` is overridden by a deeper `.gitignore`, then by
`.docswatcherignore`, then by `--exclude`. An excluded file is not read at all: it produces no
contracts, no findings, and counts as skipped.

### Syntax supported

The part of `.gitignore` that real ignore files use:

- Blank lines and `#` comments are skipped; `\#` and `\!` escape a leading `#` or `!`.
- `!pattern` re-includes what an earlier line excluded.
- A trailing `/` matches directories only.
- A pattern with a `/` anywhere but the end is anchored to the ignore file's directory; a leading
  `/` anchors and is dropped. Otherwise the pattern matches a name at any depth below it.
- `*` and `?` match within one path segment; `**` matches across segments; `[abc]` and `[!abc]`
  are character classes.
- As in git, a file inside an excluded directory cannot be re-included.

Not supported: `.git/info/exclude`, the global `core.excludesFile`, and trailing-space escapes.

### Where it applies

Both engines apply the same rules to the same file list, and a shared table of test cases proves
they agree.

- **Java engine** (CLI, Action, MCP, GitHub App): after walking the tree and before reading any
  file.
- **TypeScript engine** (the site): inside `scan`, from the ignore files present in the input.
- **The site's fetchers.** The GitHub API, jsDelivr and GitLab routes read at most 300 files. They now
  fetch the ignore files first and drop excluded paths before choosing the 300, so fixtures cannot
  crowd out real code. The tarball route already has every file.

## Test plan

| Test | Proves |
|---|---|
| One shared file of cases (patterns, paths, expected result) passes in the Java and TypeScript engines | The two engines agree, rule for rule |
| Unanchored `foo`, anchored `/foo` and `a/foo`, `dir/`, `*.json`, `**/x`, `a/**`, `a/**/b`, `?`, `[ab]`, `[!ab]` | The syntax above |
| `!` re-includes a file, but not a file inside an excluded directory | Git's own rule |
| A nested `.gitignore` applies only below its own directory, and overrides a shallower one | Scoping and order |
| `.docswatcherignore` overrides `.gitignore`, and `--exclude` overrides both | Precedence |
| A fixture repository with a `.docswatcherignore` produces the same findings from both engines, and excludes what it names | End to end, with parity |
| `match --exclude` drops a finding; the Action's `exclude` input and MCP `exclude` pass through | The three entry points |
| Scanning DocsWatcher's own clean clone with its `.docswatcherignore` finds nothing | The case that started this |
