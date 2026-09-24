# ADR 0007: GraphQL fields are found by literal rules that name them

Date: 2026-09-24
Status: accepted

## Context

Shopify's detectors only found API versions, so Shopify's own removals could not be recorded: a
change record no detector can match does not belong in the knowledge base. Shopify's Admin API is
GraphQL first. Its breaking changes name fields, not paths: the `automaticDiscounts` query and the
`marketCurrencySettingsUpdate` mutation are removed in 2027-01, `scriptTagCreate` and
`scriptTagUpdate` start returning a user error on 1 October 2026, and
`DiscountCountriesInput.includeRestOfWorld: true` becomes an error in 2027-01. Every request goes
to the same `/admin/api/<version>/graphql.json`, so the `endpoint` kind cannot tell them apart.

In code, a GraphQL operation is a string: a `#graphql` template literal passed to `admin.graphql`,
a `.graphql` file, a query in a Python or Ruby string. The literal layer already reads strings.

## Decision

Use the `graphql_operation` contract kind, which the schema has listed since the start and which
the validator, both engines and the matcher already accept, and detect it with ordinary literal
rules. No engine change.

- **Keys.** A query or mutation is keyed by the root field it selects (`automaticDiscounts`). An
  argument or input field is keyed by its GraphQL schema coordinate
  (`DiscountCountriesInput.includeRestOfWorld`), because input field names are not unique. Keys
  match exactly, like every kind other than `endpoint` and `api_version`.
- **The rule names its fields.** `shopify.literal.graphql-admin-field` is an alternation of the
  field names that change records name. A record for a newly removed field adds its name to the
  list in the same change, along with the fixture it needs anyway.
- **GraphQL syntax must follow the name.** The name must be followed by arguments written
  `name: value` or by a selection set `{`. That is what a GraphQL selection looks like and what
  prose, response handling (`data.automaticDiscounts.nodes`, `const { automaticDiscounts } =`), a
  plain function call (`automaticDiscounts(session)`) and generated TypeScript types
  (`automaticDiscounts: DiscountAutomaticConnection`) do not.
- **Schema dumps are excluded.** An SDL file declares the same fields with arguments, so
  `schema.graphql` and `*.schema.graphql` are in the rule's `exclude`.
- **Files.** Source files and `.graphql`/`.gql`. Markdown is never read by these rules, so a
  migration note or a README example produces nothing.
- **A value, when only a value breaks.** `includeRestOfWorld` is only a problem when it is `true`,
  so its rule matches `includeRestOfWorld: true` (and `=> true`, Python's `True`), in GraphQL,
  JSON variables and source.

The REST side of script tags uses the existing `endpoint` kind. Two literal rules find
`/admin/api/<version>/script_tags.json` and `.../script_tags/<id>.json`, with the version written
as a date, `unstable` or a template placeholder, and key them `ANY /admin/api/{version}/...` so every
spelling is one contract. Records match them with the existing endpoint glob,
`POST /admin/api/*/script_tags.json`.

## Rejected alternatives

- **One generic rule for every query and mutation.** A pattern like
  `(query|mutation)[^{]*\{\s*(\w+)` would inventory every operation, but a literal rule belongs
  to one provider and runs on every repository. GitHub's GraphQL, or any other, would be filed
  under Shopify. Gating literals on a manifest (`requires`) would fix provenance but needs an engine
  change in both engines, and still misses apps that call the Admin API with `fetch` and no SDK.
  It also only sees the first root field of a document.
- **A new `graphql_field` kind.** `graphql_operation` already exists across the schema, the
  validator, both engines' types and the web labels. A second kind for the same thing would be a
  schema version bump with nothing gained; the schema coordinate key covers input fields.
- **Parsing GraphQL documents.** A tree-sitter GraphQL grammar would find fields structurally,
  but documents are embedded in strings of other languages, the grammar would need pinning and
  shipping as WebAssembly for the browser, and the literal rule is already precise on the
  fixtures.

## Consequences

- The inventory shows only the GraphQL fields some record names, not every operation an app runs.
  That is enough for findings, which is the product; it is not a GraphQL usage report.
- Adding a removed field is a one-word change to the rule plus the record and its fixture.
- The script tag endpoint key cannot see the HTTP method, so a `GET` of `script_tags.json` also
  matches the records that affect `POST` and `PUT`. The records say which calls break.
- `check_api` accepts `graphql_operation` as an explicit kind and tries a shapeless value against
  it, so an agent asking about `automaticDiscounts` gets the Shopify record.
- Fixtures: `shopify-remix-graphql-removals`, `shopify-node-script-tags`, and the negative
  `shopify-negative-graphql-prose`, which holds a migration note, response handling and a schema
  excerpt that all mention the names and must produce nothing.
