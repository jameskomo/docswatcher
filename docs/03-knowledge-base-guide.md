# Knowledge base contributor guide

The knowledge base is the open-source half of DocWatcher. It holds every provider we understand, every rule that finds a provider in code, every deprecation we know about, and the fixture that proves each one is detectable. Both engines, Java and TypeScript, are interpreters over this directory. If you can write YAML, you can add a provider.

Formats are defined in `docs/02-schemas.md`. This guide covers the workflow. The two rules that matter most:

1. **No change without a fixture.** A change record is not merged unless a fixture demonstrates that the engines find it.
2. **Detectors are data.** Never fix a false positive in engine code. Fix the rule.

## Directory layout

```
knowledge/
  providers/
    stripe/
      provider.yaml          identity, base URLs, docs, spec source
      detectors.yaml         manifests, literals, call sites
      methods.yaml           SDK method to endpoint table, generated or hand-written
      changes/
        stripe-charges-sunset-2026.yaml
        stripe-sources-retired-2025.yaml
    openai/
      ...
  fixtures/
    stripe-java-charges/
      repo/
      expected-inventory.json
      expected-findings.json
    stripe-negative-readme-only/
      repo/
      expected-inventory.json
      expected-findings.json
  scripts/
    validate            schema, dates, fixture coverage, regex dialect
    gen-methods         regenerate methods.yaml from a pinned OpenAPI spec
    check-links         weekly URL check
```

### provider.yaml

```yaml
id: stripe
name: Stripe
homepage: https://stripe.com
docs: https://stripe.com/docs/api
changelog: https://stripe.com/docs/upgrades
base_urls:
  - https://api.stripe.com
openapi:
  url: https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.yaml
  pinned_sha: 8c1f0a2
sdk_languages: [java, python, typescript, go, ruby, php, csharp]
deprecation_policy: >
  Versioned by date header. Old API versions are kept alive for years but
  individual endpoints receive Sunset headers before removal.
```

The `openapi.pinned_sha` is the commit of the spec that `methods.yaml` was generated from. Bump it deliberately and regenerate in the same PR.

## Adding a provider

1. Create `providers/<id>/provider.yaml`. The `id` is lower case, letters and hyphens only, and it becomes the `provider` field on every contract.
2. Create `detectors.yaml` with at least the `manifests` block. A provider with no manifest rules cannot unlock call-site detection, so this block is mandatory even for HTTP-only providers. Use the package names for every ecosystem the provider publishes an SDK in.
3. Add at least one literal rule. For most providers this is the base URL pattern. For AI providers it is the model ID pattern.
4. Add one positive fixture per ecosystem you cover and one negative fixture. See the fixture section below.
5. Run `scripts/validate` from the `knowledge/` directory. Open a PR.

Do not add change records in the same PR as a new provider. Land the provider first so reviewers can judge detection quality on its own.

## Adding a detector

Detectors have three layers. Pick the cheapest one that identifies the contract reliably.

| Layer | Use when | Confidence | Cost |
|---|---|---|---|
| Manifest | The provider ships an SDK package | high | Near zero |
| Literal | The contract appears as a string: URL, model ID, version pin | medium | Regex over every matching file |
| Call site | The contract is only visible as an SDK method call | high | Tree-sitter parse of the language |

Every detector needs a stable `id` in the form `<provider>.<layer or language>.<name>`. Evidence records carry it forever, so never rename one. Add a new rule and remove the old one instead.

### Literal rules

The `pattern` is a regular expression that must run identically in Java and JavaScript. The validator rejects anything outside the shared dialect. The rules:

- Allowed: character classes, quantifiers, non-capturing groups, alternation, `\b`, `\d`, `\w`, `\s`, anchors, lazy quantifiers, numbered capture groups.
- Not allowed: lookbehind, named groups, possessive quantifiers, atomic groups, Unicode property escapes, inline flags, backreferences.
- Case-insensitive matching is a separate `ignore_case: true` field, never an inline flag.

The `key` template refers to capture groups as `$1`, `$2`. The `files` globs decide which files are read at all. Be narrow. A rule with `files: ["**/*"]` reads every byte in the repo and needs a good reason.

### Call-site rules

A call-site rule is a tree-sitter query. Write it against the pinned grammar version listed in `engine/grammars.lock`, and test it in the tree-sitter playground for that grammar before committing. Requirements:

- One capture must be named `@call`. That node's position becomes the evidence.
- The rule must declare `requires`, the manifest package that unlocks it. This is the precision guard. A rule with no `requires` is rejected.
- `maps_to` is optional but strongly preferred. It turns an SDK method hit into an endpoint contract, which is what change records usually target.
- Predicates are limited to `#eq?`, `#match?`, and `#not-eq?`, which behave identically in java-tree-sitter and web-tree-sitter.

## Adding a change record

A change record is one deprecation event. Create `providers/<id>/changes/<id>.yaml` following section 2 of the schemas document.

Guidance beyond the schema:

- **Cite a primary source.** A provider page, changelog entry, or observed Sunset header. A blog post summarising it is a secondary source and does not count on its own.
- **One event per record.** If a provider retires three models on one day, that is three records. Findings, snoozes, and fix PRs are per record.
- **Start as `draft`** if the effective date is announced but the migration path is unclear. Drafts are validated but never produce findings. Promote to `active` in a follow-up PR once `migration` is filled in.
- **Write `migration.notes` for a coding agent.** This text is fed verbatim as context to the fix PR. Say what to replace, what to rename, and which behaviours differ. Do not write marketing copy.
- **Effort** is a judgement of a typical single-service migration: `small` is a rename or ID swap, `medium` touches call sites and tests, `large` changes data flow or webhook handling.

### Method tables

`methods.yaml` maps SDK method names to endpoints per language. It is what `maps_to` values are checked against, so a call-site rule cannot map to an endpoint the table does not know.

| Provider has | How the table is produced |
|---|---|
| A public OpenAPI spec with SDK operation hints | `scripts/gen-methods <provider>` reads the pinned spec and writes the table. Committed output is checked in CI against a fresh generation. |
| A public spec without SDK hints | Generated for endpoints, method names filled by hand and marked `source: manual`. |
| No spec | Hand-written from SDK source, every entry marked `source: manual` with a link to the SDK file it came from. |

Stripe, OpenAI, Twilio, and Shopify publish specs. Slack and GitHub publish specs without SDK hints. AWS SDK method tables are generated from the service model files in the SDK repositories, which serve the same purpose.

## Fixtures

A fixture is the proof. It is a tiny repository, ten to twenty files at most, plus the exact inventory it must produce and the findings it must yield.

```
fixtures/openai-python-model-config/
  repo/
    requirements.txt
    src/config.py
    src/summarizer.py
  expected-inventory.json
  expected-findings.json
```

Rules:

- **Real code, minimal.** Copy the shape of a real call site. Do not write a file that is only the matched string.
- **One idea per fixture.** A fixture that covers Stripe and Twilio together is two fixtures.
- **Negatives are mandatory.** Every provider has at least one fixture where the provider is mentioned but not called. Typical negatives: a README that names the provider, a test double that mocks the SDK without importing it, a comment containing an endpoint URL. These fixtures expect an empty contracts array or a `low` confidence contract only.
- **Expected output is generated, then reviewed.** Run the CLI with `--write-expected` on the fixture, then read the JSON as if reviewing a PR. Do not hand-edit it. If it is wrong, the rule is wrong.
- **Both engines must agree.** CI runs the Java and TypeScript engines on every fixture and diffs the output byte for byte.

### The no-change-without-a-fixture rule

CI fails if any `active` change record is not referenced in at least one `expected-findings.json`. This means every deprecation we publish is one we have demonstrated we can find in code. A change record for which no detector can produce a matching contract is not a finding, it is a blog post, and it does not belong here.

## Worked example: a retired model ID

The provider `exampleai` retires `example-model-1` on a future date and recommends `example-model-2`. Placeholder names throughout, so nothing here asserts a real retirement.

### Step 1. Confirm the detector exists

`providers/exampleai/detectors.yaml` already has a model literal:

```yaml
provider: exampleai

manifests:
  - ecosystem: pypi
    package: exampleai
  - ecosystem: npm
    package: "@exampleai/sdk"

literals:
  - id: exampleai.literal.model-id
    kind: model
    files: ["**/*.{py,ts,js,java,kt,go,rb,yml,yaml,toml,env,properties,json}"]
    exclude: ["**/node_modules/**", "**/*.lock", "**/package-lock.json"]
    pattern: '\b(example-model-[0-9][0-9a-z.-]*)\b'
    key: "$1"
    confidence: medium
```

No new detector is needed. The pattern already captures the whole family.

### Step 2. Write the change record

`providers/exampleai/changes/exampleai-example-model-1-retired-2027.yaml`:

```yaml
id: exampleai-example-model-1-retired-2027
provider: exampleai
kind: retired
severity: breaking
title: example-model-1 retired
summary: >
  Requests naming example-model-1 return an error after the effective date.

affects:
  - kind: model
    match: "example-model-1"

announced: 2026-09-01
effective: 2027-03-01

sources:
  - kind: provider_page
    url: https://exampleai.example/deprecations#example-model-1
    observed: 2026-09-15

migration:
  replacement: "example-model-2"
  guide: https://exampleai.example/docs/migrating-to-example-model-2
  effort: small
  notes: >
    Replace the model ID string. example-model-2 accepts the same request
    shape. Default max output tokens are higher, so tests that assert on
    truncation length may need updating.

status: active
```

### Step 3. Write the fixture

`fixtures/exampleai-python-model-config/repo/requirements.txt`:

```
exampleai==2.4.0
```

`fixtures/exampleai-python-model-config/repo/src/config.py`:

```python
SUMMARY_MODEL = "example-model-1"
FALLBACK_MODEL = "example-model-2"
```

`fixtures/exampleai-python-model-config/repo/src/summarizer.py`:

```python
import exampleai
from app.config import SUMMARY_MODEL

client = exampleai.Client()

def summarize(text: str) -> str:
    response = client.generate(model=SUMMARY_MODEL, input=text)
    return response.text
```

Generate the expected inventory:

```
docwatcher scan fixtures/exampleai-python-model-config/repo --write-expected
```

`expected-inventory.json` after review:

```json
[
  {
    "id": "exampleai:model:example-model-1",
    "provider": "exampleai",
    "kind": "model",
    "key": "example-model-1",
    "confidence": "medium",
    "evidence": [
      {
        "path": "src/config.py",
        "line": 1,
        "column": 18,
        "snippet": "SUMMARY_MODEL = \"example-model-1\"",
        "detector": "exampleai.literal.model-id",
        "layer": "literal"
      }
    ],
    "context": {
      "sdk": { "ecosystem": "pypi", "package": "exampleai", "version": "2.4.0" }
    }
  },
  {
    "id": "exampleai:model:example-model-2",
    "provider": "exampleai",
    "kind": "model",
    "key": "example-model-2",
    "confidence": "medium",
    "evidence": [
      {
        "path": "src/config.py",
        "line": 2,
        "column": 19,
        "snippet": "FALLBACK_MODEL = \"example-model-2\"",
        "detector": "exampleai.literal.model-id",
        "layer": "literal"
      }
    ],
    "context": {
      "sdk": { "ecosystem": "pypi", "package": "exampleai", "version": "2.4.0" }
    }
  },
  {
    "id": "exampleai:sdk_package:exampleai",
    "provider": "exampleai",
    "kind": "sdk_package",
    "key": "exampleai",
    "confidence": "high",
    "evidence": [
      {
        "path": "requirements.txt",
        "line": 1,
        "column": 1,
        "snippet": "exampleai==2.4.0",
        "detector": "exampleai.manifest.pypi",
        "layer": "manifest"
      }
    ],
    "context": {
      "sdk": { "ecosystem": "pypi", "package": "exampleai", "version": "2.4.0" }
    }
  }
]
```

Note that `example-model-2` appears in the inventory too. That is correct. The inventory lists every contract. Only the change record decides which ones are findings.

`expected-findings.json`:

```json
[
  { "contract": "exampleai:model:example-model-1", "change": "exampleai-example-model-1-retired-2027" }
]
```

### Step 4. Add the negative

`fixtures/exampleai-negative-readme-only/repo/README.md` mentions `example-model-1` in prose and there is no manifest. Expected inventory contains the model contract at `low` confidence with `layer: literal` and no `sdk` context. Expected findings is an empty array, because the matcher ignores `low` confidence contracts by default.

### Step 5. Validate and open the PR

```
scripts/validate
```

It checks the schema, the date order, the regex dialect, that the change is referenced by a fixture, and that both engines agree on every fixture.

## Review checklist for a knowledge base PR

Reviewers check every item. A PR that fails one is sent back, not merged with a note.

- [ ] Every new or changed detector has a stable `id` and no existing `id` was renamed.
- [ ] Every literal pattern passes the shared-dialect validator and has narrow `files` globs.
- [ ] Every call-site rule declares `requires` and captures `@call`.
- [ ] Every `maps_to` endpoint exists in `methods.yaml`.
- [ ] Every change record cites a primary source with an `observed` date.
- [ ] `effective` is after `announced`, and an `active` record has a future or null `effective`.
- [ ] `migration.notes` reads as instructions to a coding agent, not as a summary.
- [ ] Every active change is referenced by at least one `expected-findings.json`.
- [ ] Expected inventories were generated by the CLI, then reviewed, not hand-edited.
- [ ] Both engines produce identical output on every touched fixture.
- [ ] A negative fixture exists for every touched provider.
- [ ] `openapi.pinned_sha` was bumped only if `methods.yaml` was regenerated in the same PR.

## Launch providers

In priority order. Deprecation velocity decides the order, because a provider that changes often is what keeps findings flowing.

| Order | Provider | Why first |
|---|---|---|
| 1 | OpenAI | Retires model IDs every few months, and model strings sit in config files everywhere. |
| 2 | Anthropic | Same pattern of model retirements, and a growing share of new codebases. |
| 3 | Google AI | Frequent model renames and retirements across the Gemini family. |
| 4 | Shopify | Quarterly API versions with hard removal after twelve months, and a large app ecosystem forced to migrate every year. |
| 5 | Stripe | The canonical example of long-running endpoint sunsets, and present in most commercial codebases. |
| 6 | Twilio | Regular behaviour changes to webhooks and callbacks that break parsers silently. |
| 7 | SendGrid | Legacy v2 endpoints still widely called, and versioned removals announced in advance. |
| 8 | Slack | Scope and method deprecations with dates, plus rate-limit changes. |
| 9 | GitHub | REST and GraphQL deprecations with Sunset headers, and rate-limit changes that affect batch jobs. |
| 10 | AWS SDK | Major SDK version sunsets across many languages, with per-service model files that generate method tables. |
