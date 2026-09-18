# DocWatcher web

The public site, the browser scanner, and the dashboard. Nuxt 4, Vue 3, TypeScript. The scan engine in `engine/` is a pure TypeScript interpreter of the knowledge base and runs unchanged in the browser and in Node.

## Layout

```
engine/            TypeScript engine: manifests, literals, callsites (web-tree-sitter), matcher, canonical JSON
app/               Nuxt app: pages (/, /calendar, /app, /app/findings/:id), components, composables, utils
scripts/
  bundle-knowledge.mjs   reads ../knowledge into generated/*.json and copies wasm grammars to public/grammars
  parity.mjs             runs the engine on every fixture and compares to expected files
  relativize.mjs         post-generate: makes every asset URL relative so the export works at any subpath
  serve-export.mjs       serves .output/public under a prefix for the subpath check
tests/unit/        vitest
tests/e2e/         Playwright, run against the exported site under /some/deep/prefix
generated/         built by the bundle step, not committed
public/grammars/   copied by the bundle step, not committed
```

## Run

```
npm install            # also runs nuxt prepare
npm run dev            # bundles knowledge, then Nuxt dev server
npm test               # vitest unit tests (bundles knowledge first)
npm run parity         # two-engine parity check over knowledge/fixtures
npm run generate       # static export to .output/public with relative asset URLs
npm run e2e            # generate, then Playwright (chromium) against the export served under a deep prefix
npm run serve-export   # node scripts/serve-export.mjs 8080 /some/deep/prefix
```

First time only: `npx playwright install chromium`.

## Grammars

Call-site detection uses web-tree-sitter 0.27 with the prebuilt wasm files that the official grammar packages publish on npm: `tree-sitter-java` 0.23.5, `tree-sitter-python` 0.25.0, `tree-sitter-typescript` 0.23.2 (typescript and tsx), `tree-sitter-javascript` 0.25.0, `tree-sitter-go` 0.25.0. These are the same versions the Java engine pins. They are installed with `--ignore-scripts` so npm does not try to compile their native addons. The `tree-sitter-wasms` package was tried first and rejected: its grammars were built with tree-sitter 0.20 and web-tree-sitter 0.27 refuses to load them.

Grammars load lazily, one per language present in the scanned tree, from `./grammars/` relative to the page.

## Relay

Set `NUXT_PUBLIC_RELAY_URL` to the Cloudflare Worker that streams GitHub tarballs (`<relay>/tarball/<owner>/<repo>[/<ref>]`). Without it, GitHub URL scans fall back to the GitHub API: one tree call, then file reads from `raw.githubusercontent.com`, capped at 300 relevant files, with an optional personal token kept in memory. The API fallback is limited to 60 requests per hour per address without a token.

## Static export and artifact hosting

`npm run generate` produces `.output/public` with hash routing and relative asset URLs, so the whole site works when its `index.html` is served from any path. The e2e suite proves it by serving under `/some/deep/prefix`. The export is about 5 MB including all six grammars and the tree-sitter runtime, well under the 16 MB artifact limit. `index.html` is the page; `_nuxt/` and `grammars/` are its supporting files.

## Design

Tokens live in `app/assets/main.css` with a light palette on `:root` and dark redefinitions under both `prefers-color-scheme` and `[data-theme="dark"]`. Type is IBM Plex Sans and IBM Plex Mono from Google Fonts. Status colors are reserved for severity and always paired with a glyph and a label.
