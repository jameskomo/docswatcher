# Your own APIs

Your platform team is switching off `/v1/orders` on 31 March. The teams that call it should hear
about it the way they hear about an OpenAI model shutdown: a finding in CI with the file and line,
an issue from the GitHub App, an answer when their coding agent asks. DocsWatcher does that for
your internal services with the same records it uses for public providers. The decision and the
alternatives are in [ADR 0009](adr/0009-your-own-apis.md).

## In one paragraph

Describe the service in a `.docswatcher/` directory, in the knowledge base's own format: a
provider, the rules that find calls to it, and a change record for each deprecation. Put that
directory in the repository that calls the service to try it, or in your organisation's
`.docswatcher` repository so every repository hears about it. Every DocsWatcher surface reads a
scanned repository's `.docswatcher/` on its own; the GitHub App also reads the organisation's, and
the CLI and the Action take it as `--knowledge-extra` and `knowledge`.

## The layout

```text
.docswatcher/
  providers/
    internal-orders/
      provider.yaml       # what the service is
      detectors.yaml      # how code that calls it is found
      changes/
        internal-orders-v1-sunset-2027.yaml   # one file per deprecation
```

The schemas are the knowledge base's ([02-schemas.md](02-schemas.md)), and the
[knowledge base guide](03-knowledge-base-guide.md) explains how to write good rules. Only the
naming rules and a few checks differ; see [Rules](#rules).

### provider.yaml

```yaml
id: internal-orders            # starts with internal-, the same as the directory name
name: Orders service           # shown in every finding
homepage: https://orders.internal.acme.dev
docs: https://docs.acme.dev/orders
changelog: https://docs.acme.dev/orders/changelog
base_urls:
  - https://orders.internal.acme.dev   # lets check_api recognise a full URL
deprecation_policy: >
  Each major version is supported for six months after its successor ships.
```

### detectors.yaml

Pick whichever rules describe how your service is called. An HTTP service with no client library
needs only a literal rule; a client package adds a manifest rule and, if you like, call-site
rules.

```yaml
provider: internal-orders

manifests:                     # optional for your own providers
  - ecosystem: npm
    package: "@acme/orders-client"

literals:
  - id: internal-orders.literal.endpoint     # starts with the provider id and a dot
    kind: endpoint
    files: ["**/*.{ts,tsx,js,mjs,py,go,java,yaml,yml,env}"]
    pattern: 'orders\.internal\.acme\.dev(/v[0-9]+/[a-z_-]+(?:/[a-z_-]+)*)'
    key: "ANY $1"
    confidence: medium

callsites:
  - id: internal-orders.ts.create-order
    language: typescript
    kind: sdk_method
    requires: "@acme/orders-client"          # a manifest rule above
    query: |
      (call_expression
        function: (member_expression property: (property_identifier) @m)
        (#eq? @m "createOrder")) @call
    key: "OrdersClient.createOrder"
    maps_to:
      kind: endpoint
      key: "POST /v1/orders"
```

### A change record

```yaml
id: internal-orders-v1-sunset-2027          # starts with the provider id and a dash
provider: internal-orders
kind: sunset
severity: breaking
title: Orders API v1 is switched off
summary: >
  The Orders service stops serving /v1 on 31 March 2027. Every v1 endpoint has a v2
  equivalent with the same fields, apart from amounts, which are integers in minor units.
affects:
  - kind: endpoint
    match: "ANY /v1/orders"
  - kind: endpoint
    match: "ANY /v1/orders/*"
  - kind: sdk_method
    match: "OrdersClient.createOrder"
announced: 2026-09-01
effective: 2027-03-31
sources:
  - kind: changelog
    url: https://docs.acme.dev/orders/changelog#v1-sunset
    observed: 2026-09-01
migration:
  replacement: "OrdersClient.placeOrder (POST /v2/orders)"
  guide: https://docs.acme.dev/orders/migrate-to-v2
  effort: small
  notes: >
    Upgrade @acme/orders-client to 2.x, call placeOrder instead of createOrder, and send
    the amount in minor units.
status: active
```

A record with `status: draft` is validated but produces no findings, so a team can write it before
the date is agreed and switch it to `active` when it is announced. `withdrawn` keeps the history of
a plan that was dropped.

This exact example is the knowledge base fixture
[`internal-orders-own-records`](../knowledge/fixtures/internal-orders-own-records/repo), which both
engines scan in every build.

## Check your records

```bash
docswatcher validate .docswatcher
```

```text
Your own API records from .docswatcher: 1 provider, 2 change records, checked against knowledge base 2026.09.24
OK · 0 errors, 0 warnings
```

`docswatcher validate` with no argument does the same for `./.docswatcher` when there is one, and
`--knowledge-extra <dir>` adds more directories. Records are checked against the knowledge base
they will be added to, so a clash with a bundled provider or record is caught here.

## Sharing them with every repository

### Your organisation's `.docswatcher` repository

Create a repository named `.docswatcher` in the organisation, with the records in its own
`.docswatcher/` directory, exactly as above. The team that owns a service opens a pull request
there when it deprecates something. One repository, one layout, and nothing is copied.

### The GitHub App

Install the App on the `.docswatcher` repository along with the others. Then:

- every scan of every repository of that owner reads the shared records as well as the
  repository's own;
- a push to the `.docswatcher` repository's default branch rescans every other repository of the
  owner, so a new deprecation reaches every caller within minutes, not at their next push;
- the `.docswatcher` repository's own check run shows whether its records are valid.

If the records are invalid, or the repository cannot be read, scans still run on the bundled
knowledge base. The check run says the records were not used and lists every error, and is
neutral rather than green. Findings that came from the records stay open until the records can be
read again; they are not closed as fixed.

### The GitHub Action

Check the shared repository out beside your code and pass its records to the `knowledge` input:

```yaml
jobs:
  docswatcher:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/checkout@v4
        with:
          repository: acme/.docswatcher
          path: org-records
          token: ${{ secrets.DOCSWATCHER_RECORDS_TOKEN }}   # read access, if the repository is private
      - uses: jameskomo/docswatcher@v0
        with:
          knowledge: org-records/.docswatcher
```

The scanned path's own `.docswatcher/` is read without any input. `knowledge` takes one directory
per line. Invalid records fail the step with every error in the log: a scan without them would
pass a repository that was never checked against them.

### The command line

```bash
docswatcher match . --format text                                  # reads ./.docswatcher on its own
docswatcher match . --knowledge-extra ../org-records/.docswatcher  # and the organisation's
```

### Coding agents

`docswatcher mcp` reads the `.docswatcher/` of the directory it starts in, plus any
`--knowledge-extra`, so `check_api` answers for your services too:

```text
> check_api {"value": "https://orders.internal.acme.dev/v1/orders"}
RETIRING in 188 days (2027-03-31): Orders API v1 is switched off [Orders service]. Replacement: OrdersClient.placeOrder (POST /v2/orders).
  Guide: https://docs.acme.dev/orders/migrate-to-v2
```

If the records are invalid the server still starts, and every answer ends by saying they were not
loaded and naming the first error.

### The browser

A scan on the site reads the scanned repository's `.docswatcher/`, whatever its file budget or
ignore files say, and says above the results what it read or lists every error. It cannot read an
organisation's private `.docswatcher` repository, and does not ask for access to it.

## Rules

Your records are checked with the knowledge base's validator, with these differences:

| | Knowledge base | Your own records |
|---|---|---|
| Provider id | Anything but `internal-…` | Must start with `internal-`, and match its directory name |
| Detector ids | Unique | Must start with the provider id and a dot: `internal-orders.literal.endpoint` |
| Change ids | Unique | Must start with the provider id and a dash: `internal-orders-v1-sunset-2027` |
| Change `provider` | Any provider | One of your own providers; a bundled provider's deprecations belong upstream |
| Manifest rule | At least one | Optional |
| Fixtures | Every change needs one, every provider a negative | Not required |
| `status` disagrees with `effective` | Error | Warning, so a date passing never fails a build |
| `sources[].url`, `migration.guide` | Any | `https://` or `http://` only |

The prefixes mean nothing you write can clash with a bundled provider, now or when a knowledge base
release adds one with the same name, and every finding says whose record it came from:
`internal-orders:endpoint:ANY /v1/orders`.

A file under `changes/` must end in `.yaml`; a `.yml` file is reported rather than ignored. A
`.docswatcher/` directory is never scanned as code.

A literal pattern of your own runs under a two second budget per file on the JVM (the CLI, the
Action and the App). A pattern that backtracks past it stops the scan with the rule's name; make
it more specific.

## What is not there yet

- The App's dashboard and fix pull requests show a finding from your own record by its change id
  rather than its title; its issue and check run carry the full record.
- The App reads `.docswatcher` of the repository's owner only, not of other organisations.
- Records are only as good as their detectors. As with public providers, a call built from
  fragments at run time is not found; the [knowledge base guide](03-knowledge-base-guide.md) has
  the patterns that work.
