# Schemas

These four documents are the contracts between every component of DocsWatcher. The engine emits the inventory. The knowledge base supplies change records and detector tables. Fixtures prove that a detector finds what a change record describes. Everything else, the CLI, the app, the browser scanner, the dashboard, reads or writes one of these and nothing else.

Rule: a component may only depend on another through one of these documents. No shared code between the Java engine and the TypeScript browser engine, only shared data.

## 1. Inventory document

Produced by a scan. One per repo per commit. JSON.

```json
{
  "schemaVersion": "1",
  "repo": {
    "host": "github",
    "owner": "acme",
    "name": "checkout-service",
    "ref": "refs/heads/main",
    "sha": "3f9c2a1e..."
  },
  "scannedAt": "2026-09-18T14:02:11Z",
  "engine": {
    "name": "docswatcher-engine-java",
    "version": "0.1.0",
    "knowledgeVersion": "2026.09.18"
  },
  "stats": {
    "filesScanned": 412,
    "filesSkipped": 38,
    "durationMs": 840,
    "layers": ["manifest", "literal", "callsite"]
  },
  "contracts": [
    {
      "id": "stripe:endpoint:POST /v1/charges",
      "provider": "stripe",
      "kind": "endpoint",
      "key": "POST /v1/charges",
      "confidence": "high",
      "evidence": [
        {
          "path": "src/payments/ChargeClient.java",
          "line": 88,
          "column": 9,
          "snippet": "Charge.create(params)",
          "detector": "stripe.java.charge-create",
          "layer": "callsite"
        }
      ],
      "context": {
        "sdk": { "ecosystem": "maven", "package": "com.stripe:stripe-java", "version": "22.4.0" },
        "apiVersion": "2020-08-27"
      }
    }
  ]
}
```

### Contract fields

| Field | Values | Notes |
|---|---|---|
| `id` | `provider:kind:key` | Stable across scans. Findings and suppressions key on it. |
| `provider` | slug from the knowledge base | Lower case, e.g. `stripe`, `openai`, `shopify`. |
| `kind` | `sdk_package`, `sdk_method`, `endpoint`, `model`, `api_version`, `graphql_operation`, `webhook` | Closed set. Adding one is a schema version bump. |
| `key` | kind-specific string | Endpoint: `METHOD /path`. Model: the model ID. API version: the version string. SDK method: dotted path as the SDK names it. GraphQL operation: the root field a query or mutation selects (`automaticDiscounts`), or the `Type.field` schema coordinate of an argument or input field (`DiscountCountriesInput.includeRestOfWorld`). |
| `confidence` | `high`, `medium`, `low` | The rule's own confidence, downgraded to `low` when every evidence path is a documentation or test path. Documentation paths end in `.md`, `.rst`, `.txt`, or `.adoc`. Test paths contain a segment named `test`, `tests`, `__tests__`, `spec`, `specs`, or `fixtures`, or a file name containing `.test.`, `.spec.`, or `_test.`, or ending in `Test.java`, `Test.cs`, or `Tests.cs`. Low-confidence contracts are hidden by default. Evidence from the manifest layer never counts as documentation or test, so a dependency in `requirements.txt` stays high. |
| `evidence[]` | at least one | Every location the contract was observed. `detector` is the ID of the rule that fired. `layer` is `manifest`, `literal`, or `callsite`. `line` and `column` are 1-based; `column` is where the match starts, and for a literal rule with a capture group it is where group 1 starts. `snippet` is the full source line with leading and trailing whitespace removed, truncated to 200 characters. |
| `context` | optional | `sdk` is present on every contract of a provider when exactly one manifest rule for that provider matched, and holds that package's ecosystem, name, and version (version is `null` when the manifest gives none). `apiVersion` is present on every contract of a provider when exactly one `api_version` contract exists for that provider. Otherwise the field is omitted. |

Ordering: contracts sorted by `id`, evidence sorted by `path`, then `line`, then `column`, then `detector`. Two evidence entries that are equal on all six fields collapse into one. The parity test compares documents byte for byte, so ordering is part of the schema.

Clarifications fixed by the Java engine, which the TypeScript engine must mirror:

- A contract's confidence starts as the highest confidence among the rules that produced its evidence, then the documentation and test downgrade applies.
- Globs are matched against the slash-separated path relative to the repo root. `**` crosses directories, `**/` also matches nothing so `**/*.java` matches a top-level file, `*` and `?` stay inside one segment, and `{a,b}` alternates.
- A call-site rule with `requires` runs only when that exact package matched a manifest. A rule without `requires` runs when any manifest rule of its provider matched.
- Files are read as UTF-8. A file over 1 MB, or one with a NUL byte in its first 8 KB, is skipped and counted in `filesSkipped`. Skipped directories are not counted.
- `filesScanned` counts every text file the walk visited, whether or not any rule matched it.
- `stats.incomplete` is present only when a whole-scan limit stopped the scan (docs/10-reference.md, "Scan limits"): `{"limit": "maxFiles" | "maxBytes" | "maxDuration", "max": <files, bytes or milliseconds>, "filesNotScanned": <n>}`. Files after the stop are counted there, not in `filesSkipped`. The contracts are then those found in the files scanned, not the repository's.
- The manifest evidence column points at the start of the package name: after the opening quote in `package.json`, `composer.json`, `Gemfile`, and a `.csproj` `Include` attribute, at the artifact id text in `pom.xml`, at the module path in `go.mod`, and at the start of the line in requirements files.

## 2. Change record

One per deprecation event. YAML, lives in `knowledge/providers/<provider>/changes/<id>.yaml`.

```yaml
id: stripe-charges-sunset-2026
provider: stripe
kind: sunset
severity: breaking
title: Charges API sunset
summary: >
  POST /v1/charges and related Charge operations stop accepting new
  requests. Existing charges remain readable. Migrate to PaymentIntents.

affects:
  - kind: endpoint
    match: "POST /v1/charges"
  - kind: sdk_method
    match: "Charge.create"
  - kind: api_version
    match: "< 2022-11-15"

announced: 2025-11-30
effective: 2026-11-30

sources:
  - kind: changelog
    url: https://stripe.com/docs/upgrades#2026-changes
    observed: 2026-09-01
  - kind: sunset_header
    observed: 2026-09-10
    note: "Sunset: Mon, 30 Nov 2026 00:00:00 GMT seen on POST /v1/charges"

migration:
  replacement: "POST /v1/payment_intents"
  guide: https://stripe.com/docs/payments/payment-intents/migration
  effort: medium
  notes: >
    Charge.create(amount, currency, source) becomes PaymentIntent.create
    with confirm=true. Webhook handlers for charge.succeeded should move
    to payment_intent.succeeded.

status: active
```

### Change fields

| Field | Values | Notes |
|---|---|---|
| `id` | `provider-slug-year` | Unique across the knowledge base. Never reused. |
| `kind` | `sunset`, `retired`, `behavior_change`, `field_change`, `limit_change` | Sunset is an endpoint or version. Retired is a model or SDK version. |
| `severity` | `breaking`, `warning`, `info` | Breaking means calls will fail after `effective`. Warning means behavior differs. Info means nothing fails. |
| `affects[]` | at least one | Each entry is a `kind` from the contract kinds and a `match`. Match is an exact string, or a comparison for `api_version` (`< 2022-11-15`), or a glob for endpoints (`POST /v1/charges/*`). |
| `announced` | date | When the provider first published it. |
| `effective` | date or null | When it takes effect. Null for behavior changes with no date. |
| `sources[]` | at least one | `kind` is `changelog`, `openapi_diff`, `email`, `sunset_header`, `deprecation_header`, `provider_page`. Every source has `observed`. |
| `migration` | optional | Replacement, guide URL, effort (`small`, `medium`, `large`), and free-text notes used as context for fix PRs. |
| `status` | `draft`, `active`, `withdrawn`, `expired` | Draft records never produce findings. Expired means `effective` has passed and the record is kept for history. |

Validation rules enforced in CI:

- `effective` is after `announced` when both are set.
- `status: active` with `effective` in the past fails validation. Move it to `expired`.
- Every active or expired change must be referenced by at least one fixture's expected findings, or validation fails. An individual `affects` entry that no fixture hits is reported as a warning. See section 4.
- Every `url` must resolve. Checked weekly, not per PR.

## 3. Detector table

One per provider. YAML, lives in `knowledge/providers/<provider>/detectors.yaml`. Both engines load this file and interpret it. There is no detector logic in code beyond the interpreter.

```yaml
provider: stripe

manifests:
  - ecosystem: maven
    package: com.stripe:stripe-java
  - ecosystem: npm
    package: stripe
  - ecosystem: pypi
    package: stripe
  - ecosystem: go
    package: github.com/stripe/stripe-go

literals:
  - id: stripe.literal.endpoint
    kind: endpoint
    files: ["**/*.{java,kt,ts,js,py,go,rb,php,cs}"]
    pattern: 'https://api\.stripe\.com(/v1/[a-z_]+(?:/[a-z_{}]+)*)'
    key: "ANY $1"
    confidence: medium

  - id: stripe.literal.api-version
    kind: api_version
    files: ["**/*"]
    exclude: ["**/node_modules/**", "**/target/**", "**/*.lock"]
    pattern: 'Stripe-Version["'']?\s*[:=,]\s*["''](\d{4}-\d{2}-\d{2})'
    key: "$1"
    confidence: medium

callsites:
  - id: stripe.java.charge-create
    language: java
    kind: sdk_method
    requires: com.stripe:stripe-java
    query: |
      (method_invocation
        object: (identifier) @cls
        name: (identifier) @method
        (#eq? @cls "Charge")
        (#eq? @method "create")) @call
    key: "Charge.create"
    maps_to:
      kind: endpoint
      key: "POST /v1/charges"
    confidence: high

  - id: stripe.python.charge-create
    language: python
    kind: sdk_method
    requires: stripe
    query: |
      (call
        function: (attribute
          object: (attribute object: (identifier) @mod attribute: (identifier) @cls)
          attribute: (identifier) @method)
        (#eq? @mod "stripe") (#eq? @cls "Charge") (#eq? @method "create")) @call
    key: "Charge.create"
    maps_to:
      kind: endpoint
      key: "POST /v1/charges"
    confidence: high
```

### Detector rules

- **Manifests** map an ecosystem package to the provider. A manifest hit produces one `sdk_package` contract and unlocks the provider's `callsites`. Call-site detectors never run for a provider whose package is absent from every manifest. This is the main precision guard. Manifest files and how each is read:

| Ecosystem | Files | Match | Version |
|---|---|---|---|
| `npm` | `package.json` | key in `dependencies` or `devDependencies` | the value string as written |
| `pypi` | `requirements*.txt`, `pyproject.toml` | package name, case-insensitive, before any `==`, `>=`, `[`, `;` or space | the pinned or minimum version if one is written, else `null` |
| `maven` | `pom.xml` | `groupId:artifactId` of any `<dependency>` | the `<version>` text if present, else `null` |
| `go` | `go.mod` | module path in a `require` line or block | the version token |
| `rubygems` | `Gemfile` | first string argument of a `gem` call | the second string argument if present, else `null` |
| `packagist` | `composer.json` | key in `require` or `require-dev` | the value string as written |
| `nuget` | `*.csproj` | `Include` of a `<PackageReference>`, case-insensitive | the `Version` attribute, else a nested `<Version>` element, else `null` |

The `sdk_package` contract's key is the package name as written in the detector table. Evidence points at the manifest file and the line where the package is named. Its column is where the package name starts.

- **Endpoint keys** are `METHOD /path`. A literal rule that cannot know the method writes `ANY`. A change record that affects `ANY /path` matches contracts with any method on that path, and one that affects `POST /path` matches only `POST /path` and `ANY /path`.
- **Literals** are regular expressions over file contents. `files` and `exclude` are globs. `key` is a template over capture groups. Hits inside comments, markdown, or paths matching `**/test/**` are downgraded to `low` confidence.
- **Languages** are chosen by extension: `java` for `.java`; `python` for `.py`; `typescript` for `.ts` and `.tsx`; `javascript` for `.js`, `.jsx`, `.mjs`, and `.cjs`; `go` for `.go`; `ruby` for `.rb`; `php` for `.php`; `csharp` for `.cs`. Files over 1 MB are skipped by every layer and counted in `filesSkipped`. Directories named `node_modules`, `target`, `dist`, `build`, `.git`, `vendor`, and `.venv` are never entered.
- **Call sites** are tree-sitter queries. The `query` field is the exact query source for that language's grammar. Captures named `@call` mark the evidence node. `maps_to` emits a second contract of the mapped kind with the same evidence, which is how an SDK method becomes an endpoint contract.
- **GraphQL operations** are literals with `kind: graphql_operation`. A GraphQL document in code is a string, so no call-site layer is involved. Each rule lists the field names that change records name, for example `\b(automaticDiscounts|scriptTagCreate)\s*(?:\(\s*\w+\s*:|\{)`: the name followed by GraphQL arguments or a selection set. The name list keeps the contract with its provider, since a rule for any query or mutation would claim every GraphQL API for one provider. Keys are exact strings and match exactly. See ADR 0007.
- **Model IDs** are literals with `kind: model`. Each AI provider's detector table has one literal per model family, for example `\b(gpt-[0-9][0-9a-z.-]*)\b`. The matched string is the key.

Every detector has a stable `id`. Evidence records carry it, so a false positive can be traced to the rule that fired and fixed there.

### Interpreter contract

An engine is conformant when, for every fixture in the knowledge base, it produces the expected inventory byte for byte. Two engines exist: the Java engine in `engine/` and the TypeScript engine in `web/`. Both are tested against the same fixtures on every PR.

## 4. Fixture

A fixture is a tiny repository plus the inventory it must produce plus the findings it must yield. Lives in `knowledge/fixtures/<name>/`.

```
knowledge/fixtures/stripe-java-charges/
  repo/
    pom.xml
    src/payments/ChargeClient.java
  expected-inventory.json
  expected-findings.json
```

`expected-inventory.json` holds the `contracts` array only. The repo, engine, and stats blocks are filled by the runner.

`expected-findings.json`:

```json
[
  { "contract": "stripe:endpoint:POST /v1/charges", "change": "stripe-charges-sunset-2026" },
  { "contract": "stripe:sdk_method:Charge.create", "change": "stripe-charges-sunset-2026" }
]
```

Negative fixtures exist to pin down false positives. Their expected findings array is empty. Their expected inventory is either empty or holds only `low` confidence contracts, which is what a provider name in a README or a mocked client in tests should produce. Every provider needs at least one.

The invariant enforced in CI: every active change record is referenced by at least one `expected-findings.json`. A deprecation we cannot demonstrate finding is not published.

## 5. Finding

Derived, not authored. The matcher produces it from an inventory and the active change records. The app stores it. The CLI prints it.

```json
{
  "id": "acme/checkout-service:stripe-charges-sunset-2026:stripe:endpoint:POST /v1/charges",
  "contract": "stripe:endpoint:POST /v1/charges",
  "change": "stripe-charges-sunset-2026",
  "severity": "breaking",
  "effective": "2026-11-30",
  "daysRemaining": 73,
  "evidence": [ "...copied from the contract..." ],
  "status": "open",
  "snoozedUntil": null,
  "fixPr": null
}
```

`status` is one of `open`, `snoozed`, `not_in_prod`, `not_affected`, `fixed`. Only the app changes it. The matcher is a pure function and re-derives everything else on every run.

Matcher semantics:

- A finding exists for every pair of (contract, change) where the change is `active` or `expired` and any `affects` entry matches the contract's kind and key. Expired changes still produce findings, because code that references a retired model or a removed endpoint is already broken. Draft and withdrawn changes produce nothing.
- Contracts with `low` confidence produce no findings by default. The CLI flag `--include-low` and a dashboard toggle include them.
- Suppressions are keyed on `contract` plus `change`, so a snooze survives rescans and a new change on the same contract is never hidden by an old snooze.
- `daysRemaining` is computed at match time and is negative after the effective date.
- Output is sorted by `effective` ascending, nulls last, then by `severity` (`breaking`, `warning`, `info`), then by contract id, then by change id.
- A fixture's `expected-findings.json` holds the `contract` and `change` of each finding in this order.
