# Changelog

## Unreleased

- **GitLab CI template.** `include:` `ci/gitlab/docswatcher.gitlab-ci.yml` from a release tag.
  The job downloads the Linux binary, checks it against the release's `checksums.txt` and fails
  closed like the Action does. It fails the pipeline on a breaking finding and writes a Code
  Quality report, so findings show in merge requests with their file and line. Variables:
  `DOCSWATCHER_PATH`, `DOCSWATCHER_FAIL_ON`, `DOCSWATCHER_EXCLUDE`, `DOCSWATCHER_VERSION` and more
  (`docs/11-ci-integration.md`). The old GitLab snippet used `alpine:3`, where the glibc binary
  cannot run.
- **The site scans GitLab projects.** Paste a gitlab.com project URL, nested groups included, or a
  self-managed instance's full URL where the site's policy allows that host. Share links are
  `?repo=gitlab.com/group/project`, and GitHub links are unchanged.
- **Your own APIs.** Describe your internal services' deprecations in a `.docswatcher/` directory,
  in the knowledge base's format with `internal-` ids, and every scan of that repository finds
  calls to them. Share them across an organisation through a repository named `.docswatcher`: the
  GitHub App reads it for every repository of the owner and rescans them when it changes, and the
  CLI and the Action take it as `--knowledge-extra` and the new `knowledge` input.
  `docswatcher validate .docswatcher` checks them. Invalid records are always reported: the CLI
  exits 3 and names every error. See `docs/19-your-own-apis.md`.
- A `.docswatcher/` directory is no longer scanned as code.

## 0.3.1 (2026-09-24)

- Native download for Windows x64 (`docswatcher-windows-x64.exe`). The Action runs on Windows
  runners too, and checks the download against `checksums.txt` like the others. The 0.3.0 Windows
  build left its grammar libraries out of the image; the resource patterns no longer use a
  backslash, which the Windows build mangled.
- Intel Macs still use `docswatcher.jar` with Java 25: GraalVM Community no longer builds for
  macOS x64.

## 0.3.0 (2026-09-24)

### Upgrade if you use the GitHub Action or the native binary

- **The Action never failed a build on v0.2.1 and earlier.** The Linux native binary printed `{}`
  for every finding in JSON output, so the Action counted zero breaking findings and passed.
  Anyone on `jameskomo/docswatcher@v0` gets the fix automatically, because `v0` now points at
  v0.3.0. If you pin `version:` to `v0.2.1` or earlier, remove the pin or set `v0.3.0`. The Action
  now refuses a report whose findings carry no severity, instead of counting it as clean.
- **The native binary crashed on some repositories** (any finding found in more than one place),
  exiting with an error instead of reporting. Fixed.

If your pipeline ran DocsWatcher with v0.2.1 or earlier, run it once more on v0.3.0: findings it
should have failed on were passed.

### New

- Native downloads for macOS on Apple silicon (`docswatcher-macos-arm64`). The Action picks the
  file for its runner and checks it against the release's `checksums.txt` before running it.
  Windows and Intel Macs use `docswatcher.jar` with Java 25 for now.
- Shopify: GraphQL fields are detected, with records for the `automaticDiscounts` and
  `marketCurrencySettingsUpdate` removals, `includeRestOfWorld: true` erroring in 2027-01, and the
  script tag changes (docs/adr/0007).
- Google: Gemini 2.5 models are limited to users who already used them.
- `match --report <file>` and `--inventory <file>` give every output from one scan; the Action
  now scans once.
- The Agents page: try DocsWatcher with your own Claude, OpenAI or Gemini key.

### Faster and safer

- Large repositories scan in seconds rather than minutes (llama_index: 8.5 minutes to about 12 s),
  with identical results.
- Parsing and querying each file is time-bounded, so an unusual file cannot hold up a scan.
- The GitHub App uses REST API version 2026-03-10, sends repository paths correctly, and drops a
  cached installation token as soon as the installation changes.
- `knowledge/VERSION` is the one knowledge base version everywhere.
