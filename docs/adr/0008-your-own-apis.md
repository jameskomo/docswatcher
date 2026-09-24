# ADR 0008: Your own APIs are knowledge base records in a `.docswatcher/` directory

Date: 2026-09-24
Status: accepted

## Context

The knowledge base describes third-party providers. The same question applies inside a company:
the platform team is switching off `/v1/orders` on 31 March, and the teams that call it need to
hear about it the way they hear about OpenAI or Stripe, in CI, in the browser and from the App.
The Teams page promises exactly that.

Two situations have to work:

1. **One repository declares its own service's deprecations**, for example a monorepo, or a team
   trying the records out before sharing them.
2. **Many consumer repositories pick them up without copying files.** The team that owns the
   service writes the record once; every repository in the organisation that calls the service
   finds out.

Constraints: the records must be validated by the same validator as the knowledge base; they
must never collide with a bundled provider, now or after an upgrade; errors are reported, never
silently ignored; and the Java and TypeScript engines must give identical results.

## Decision

**One format, one directory name, one way to share it.**

- **Format.** A team's records use the knowledge base layout and schema unchanged:
  `providers/<id>/provider.yaml`, `detectors.yaml` and `changes/*.yaml`
  (docs/02-schemas.md). Nothing new to learn, and the knowledge base guide applies.
- **Where.** They live in a `.docswatcher/` directory at a repository's root. Every surface reads
  the scanned repository's `.docswatcher/` automatically, the way `.docswatcherignore` is read.
  That covers situation 1.
- **Sharing.** An organisation keeps its shared records in the `.docswatcher/` directory of a
  repository named `.docswatcher` (as GitHub's `.github` repository shares community files):
  - The **GitHub App** reads `<owner>/.docswatcher` when it is installed on it, for every scan of
    every repository of that owner, and rescans them all when it is pushed to.
  - The **CLI** and the **GitHub Action** take directories of records:
    `--knowledge-extra <dir>` (repeatable) and the Action's `knowledge` input. In a workflow, the
    shared repository is checked out with `actions/checkout` and passed as
    `knowledge: org-records/.docswatcher`.

  That covers situation 2 without copying a file: the owning team changes one repository.
- **Namespacing.** Provider ids of own records start with `internal-`, and the knowledge base
  validator rejects a bundled provider that does. Detector ids start with their provider's id
  and a dot, change ids with their provider's id and a dash, and change records may describe only
  the team's own providers. Nothing a team writes can collide with a bundled record, today or
  after a knowledge base release adds a provider of the same name.
- **Validation.** `Validator.validateOwn` applies the knowledge base's checks with four
  deliberate differences: a manifest rule is optional (an internal HTTP service may have no
  client package); no fixtures are required; a status that disagrees with its date is a warning,
  not an error, so the date passing cannot fail every build that reads the records; and source
  and guide links must be http(s), because they are rendered as links. `web/engine/own.ts`
  mirrors it message for message, held by `own-knowledge-cases.json`.
- **Errors.** Any error means none of the records are used, and every surface names every error:
  - The CLI's `scan` and `match` exit 3 with the errors on stderr. A scan without the records
    would pass a repository that was never checked against them.
  - The App scans on the bundled knowledge alone, says so in the check run (neutral instead of
    success) with every error, and leaves the records' existing findings open rather than closing
    them as fixed.
  - The browser scans on the bundled knowledge and lists the errors above the results.
  - The MCP server starts, and every answer says the records were not loaded.
- **Code, not knowledge.** Neither engine scans `.docswatcher/` as source: a record's own text
  names the endpoints it describes.
- **Untrusted patterns on a server.** A team's own literal pattern runs under a two second budget
  per file in the Java engine, so a pattern that backtracks without end stops that scan with the
  rule's name instead of holding the App's worker.

## Consequences

- One parity fixture, `internal-orders-own-records`, is a repository with its own records and
  code that calls the internal endpoint and SDK. Both engines' fixture runners scan every fixture
  with its own records, so the fixture proves the same inventory and findings in both.
- The App's rematch reads the bundled knowledge only. It leaves findings of `internal-` providers
  as they are; the next scan reads the records again.
- The dashboard and the fix dispatcher look change records up in the bundled knowledge, so a
  finding from a team's own record shows its change id rather than its title there until change
  records of own providers are stored. Issues and check runs, written at scan time, carry the
  full record.
- The browser reads only the scanned repository's `.docswatcher/`. It has no way to reach an
  organisation's private records repository without credentials, which it does not ask for.

## Rejected alternatives

- **An `internal: true` flag instead of a prefix.** A flag keeps names short, but a team's
  `azure` would collide with a bundled `azure` the day the knowledge base adds one, breaking
  every build that reads it on upgrade. A prefix also says in every finding id that the record
  is the team's own.
- **Records at the root of the organisation's repository.** A root `providers/` would make that
  repository the one place where the layout differs, and scanning it would read the records as
  code. One rule, "records live in `.docswatcher/`", holds everywhere.
- **A remote reference in the Action (`owner/repo@ref`).** It needs a token with access to a
  private repository, which `actions/checkout` already handles well. A path input composes with
  it and with any other way of getting the directory there.
- **Failing the App's scan on invalid records.** The bundled findings would stop too, and the
  organisation would lose its monitoring because of one typo in one record.
