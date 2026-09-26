# Changelog

## Unreleased

- **Warned before the date.** Teams set where alerts go on the organisation dashboard: up to ten
  email addresses and a Slack incoming webhook, 30 and 7 days ahead by default. A daily job sends
  each organisation one digest per channel about open findings that just came within a threshold,
  with the repositories and links to the lines. Nothing is sent twice, a missed day is caught up
  once, and a failed channel is retried. Writers change the settings and can send a test; everyone
  who sees the organisation can read them (`docs/adr/0010-alerts-before-the-date.md`).
- **Email alerts from the calendar, for anyone.** An address and an optional provider; confirmed by
  email before anything is sent; 30 and 7 days before each tracked shutdown; one-click unsubscribe
  in every email; plain text with no tracking. New public routes `POST /subscribe`,
  `/subscribe/confirm` and `/unsubscribe`. Email is sent through Brevo; without
  `docswatcher.brevo.api-key` the app starts and says email alerts are off.
- **What actually runs.** The organisation dashboard shows, per repository, which deprecated
  calls production made (endpoint, provider, calls, last seen, and the finding each confirms or
  the provider's Sunset header) and which open endpoint findings production has never been seen
  making. Telemetry comes from the customer's own OpenTelemetry Collector with a per-repository
  ingest token, created and revoked on the dashboard by anyone with write access; only its hash
  is stored, it can write only its own repository, and the owner token keeps working (migration
  V6). A setup guide on the teams page and in the dashboard gives the Collector config, SDK
  header capture for Java, Python and Node, and what is sent and kept.
  `docs/13-runtime-observation.md` no longer suggests `http/json` for the Java and Python SDKs,
  which cannot send it.
- **The App scans in a process of its own.** Each scan runs in a separate JVM started from the
  App's own jar, with a heap cap (`docswatcher.scan.process.heap-mb`, 1024), a wall-clock limit
  (`max-seconds` plus a minute, after which it is killed) and none of the App's environment. A
  crash in the native parser, a hang or a scan that runs out of memory now fails that scan run
  with a plain message, and webhooks, the API and sign-in carry on. It adds a process start of
  about 0.6 to 1.5 s per scan; `docswatcher.scan.isolation: in-process` restores the old behaviour.
  The results are the same either way: one scan function runs in both, and a test compares every
  fixture. See ADR 0011.
- **Runs left running are recovered.** A scan run still `running` long after its time limit (a
  restart or a crash stopped it) is failed with an error starting `abandoned: ` and queued again
  once, at startup and every five minutes.
- The App's configured scan limits now apply to scans that read a team's own API records too;
  those used the engine's defaults, which are the same values unless they were changed.
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
- **Twelve new providers** (knowledge 2026.09.26: 27 providers, 309 change records, 111 fixtures):
  - Square: the v1 Payments API, OAuth RenewToken, Transactions API writes and the Labor shift
    endpoints.
  - Paystack: the deprecated Check Authorization endpoint.
  - X (Twitter) API: v1.1 media upload, search, statuses/filter and oEmbed, and the Account
    Activity replay route.
  - LinkedIn Marketing API: the sunset of every monthly `Linkedin-Version` from 202206 to 202609,
    and the legacy Lead Sync APIs.
  - YouTube Data API: `relatedToVideoId`, `comments.markAsSpam`, `commentThreads.update` and the
    v2 GData feeds.
  - Discord API: API and Gateway v6, v7 and v8, Create Guild for apps, and the old pin routes.
  - Mailchimp: Marketing API 2.0 and Export API 1.0.
  - Google Maps Platform: the Legacy Places, Directions and Distance Matrix web services, and
    Maps JavaScript API removals (Drawing library, heatmap layer, `Marker`, `KmlLayer` and more).
  - Firebase: Dynamic Links, the legacy FCM APIs, and the Admin SDK's removed send methods.
  - Salesforce: API versions 21.0 to 30.0, and SOAP `login()`.
  - HubSpot: Contact Lists v1, HubDB v2, Owners v2, Pipelines v1, and the end of numbered API
    versions.
  - Cohere: model retirements (Embed v2.0, Rerank v2.0, Aya 8B, legacy Command models) and the
    deprecated v1 endpoints.

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
