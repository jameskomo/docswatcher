# ADR 0001: Engine and backend in Java 25 with Spring Boot 4, built with Maven and GraalVM native-image

Date: 2026-09-18
Status: accepted

## Context

DocsWatcher has three runtime shapes for the same logic: a CLI that runs in CI on every push, a server worker that scans repos on webhook, and a browser scanner on the public site. The engine must start fast in CI, parse source with tree-sitter for the call-site layer, and provide robust type-safety and long-term maintainability. Development velocity, low memory footprint, and sub-second startup outrank pure micro-benchmarks.

Candidates considered: Rust, Java 25 with Spring Boot 4, Go, Python, TypeScript with Nitro.

## Decision

- Engine, CLI, and app are Java 25, built with Maven as one multi-module project.
- The app uses Spring Boot 4.
- Both the CLI and the app build as GraalVM native-image binaries in CI from day one. The JVM profile remains for local development and tests.
- The browser scanner is a separate, thin TypeScript interpreter over the same detector data. See ADR 0002.
- Tree-sitter is used through the official java-tree-sitter bindings, which rely on the Foreign Function and Memory API, final since Java 22.

## Reasons

1. **The team is fastest in Java.** Early on, speed of building dominates.
2. **Java 25 neutralises the parser argument.** The Foreign Function and Memory API gives clean tree-sitter bindings with no JNI. This was the strongest technical reason to pick Rust.
3. **Data-driven detectors neutralise the WebAssembly argument.** With detectors as data, the browser needs a small interpreter, not the whole engine. See ADR 0002.
4. **GraalVM native-image gives the CLI and server the startup and memory profile we wanted from Rust.** The app fits in under 200 MB, which is the boundary between free and paid container tiers.
5. **Spring Boot 4 has mature ahead-of-time support**, so native builds are a CI profile rather than a project.

## Rejected alternatives

- **Rust.** Best possible engine: single binary, native tree-sitter, one codebase compiling to WebAssembly for the browser. Rejected because it is a second language for the team, and its two structural advantages are matched by reasons 2 and 3 above. Revisit only if the interpreter-parity approach proves unmaintainable.
- **Go.** Fast CLI, simple deployment. Weaker tree-sitter story than Java 25 or Rust, no WebAssembly path for the engine, and no team advantage over Java.
- **Python.** Fails the CI startup requirement and offers nothing the others lack.
- **TypeScript with Nitro for everything.** One language for engine and browser, which is attractive. Rejected because the CI CLI would drag a Node dependency tree, tree-sitter in Node goes through native addons that complicate distribution, and the team is not faster in it. Nitro also does not remove the need for a frontend framework; Nuxt is kept for the web surface.

## Consequences

- Two engines exist, Java and TypeScript, and must stay in parity. The cost is bounded by keeping all detection logic in data and by the fixture parity test on every PR.
- Native-image builds take minutes. CI runs them; developers do not wait on them locally.
- Some Spring libraries need reflection hints for native-image. Choose libraries with existing hints and add tests that run the native binary, not just the JVM.
- The engine module must have no Spring dependency so it stays a plain library that contributors can run and that the benchmark corpus tests directly.

## Amendment 2026-09-18: tree-sitter bindings

v1 uses the `io.github.bonede` tree-sitter bindings (JNI) rather than the official `io.github.tree-sitter:jtreesitter` (FFM) bindings named above. The reason is packaging: bonede publishes every grammar as a Maven artifact with prebuilt native libraries for Linux, macOS, and Windows, so a contributor needs no C toolchain. The official FFM bindings ship the core only and expect us to compile each grammar's `parser.c` ourselves. The engine touches the binding through one class, so swapping later is contained. Two consequences: native-image needs JNI configuration for the binding, and the JVM prints a native-access warning unless run with `--enable-native-access=ALL-UNNAMED`. Revisit when jtreesitter or a grammar bundle removes the compile step.
