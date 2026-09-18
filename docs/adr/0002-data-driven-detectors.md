# ADR 0002: Detectors are data in the knowledge base, executed by thin interpreters

Date: 2026-09-18
Status: accepted

## Context

Detection has three layers: manifests, literals, and call sites. Two engines need to run them, Java on the server and CI, TypeScript in the browser. Writing detection logic twice invites drift, and the public browser scanner is only trustworthy if it agrees exactly with the CLI.

## Decision

All detection rules live in the knowledge base as data, in `knowledge/providers/<provider>/detectors.yaml`, in the format defined in `docs/02-schemas.md` section 3.

- Manifest rules are package names per ecosystem.
- Literal rules are regular expressions with file globs and key templates.
- Call-site rules are tree-sitter queries in the grammar's own query language, plus a mapping to the endpoint they represent.

An engine is a loader, a rule runner, and a small post-processing step that turns matches into inventory contracts. The post-processing step is the only logic written twice. It is kept identical by the parity test: both engines run every fixture on every PR and must produce byte-identical inventories.

Tree-sitter runs in the browser through the official web-tree-sitter build, with one WebAssembly grammar per language loaded lazily when that language is detected in the tree. So the deep scan, not only the shallow one, runs client-side.

## Reasons

- The knowledge base is the product's moat. Making detectors data moves more of the product into the open-source, community-editable, testable layer.
- Contributors add a provider by adding YAML and a fixture, without touching either engine.
- The parity test is only possible if the rule set is shared. Shared logic would need a shared language.
- A false positive traces to a rule ID in the evidence record and is fixed in one file.

## Rejected alternatives

- **Detection logic in Java only, browser calls the server.** Simpler, but puts a server in the path of every public scan, which costs money before revenue and makes the public demo slower and less private.
- **Detection logic in TypeScript only, Java calls out to Node.** Forces a Node runtime into the CI binary and the native image.
- **One engine compiled to both targets.** Only Rust offers this today. GraalVM 25 has an experimental WebAssembly backend for native-image that may make it possible for Java later. Watch it; do not build on it yet.

## Consequences

- Tree-sitter query semantics must be identical across java-tree-sitter and web-tree-sitter. They share the C core, so this holds, but grammar versions must be pinned identically in both engines.
- Regular expressions must use a dialect both Java and JavaScript support. The validator rejects constructs outside the common subset.
- Post-processing rules, such as downgrading confidence inside comments or test paths, are specified in prose in the schemas document and covered by dedicated fixtures so neither engine can diverge silently.
