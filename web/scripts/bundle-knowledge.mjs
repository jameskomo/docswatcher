// Reads ../knowledge/** into web/generated/{knowledge,samples}.json, writes the public feeds into
// public/feeds/, and copies wasm grammars into public/grammars.
import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync, existsSync, copyFileSync } from "node:fs";
import { join, relative, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { loadKnowledge } from "./lib/knowledge.mjs";
import { writeFeeds } from "./lib/feeds.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, "..");
const knowledgeDir = join(web, "..", "knowledge");
const outDir = join(web, "generated");
mkdirSync(outDir, { recursive: true });

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p)); else out.push(p);
  }
  return out;
}

const knowledge = loadKnowledge(knowledgeDir);
const { version, providers } = knowledge;
writeFileSync(join(outDir, "knowledge.json"), JSON.stringify({ version, providers }, null, 2) + "\n");

// Calendar, feed and open data. Static files the site's nginx serves as they are.
const feeds = writeFeeds(knowledge, join(web, "public", "feeds"), { siteUrl: process.env.DOCSWATCHER_SITE_URL });

const samples = [];
const skipped = [];
const fixturesDir = join(knowledgeDir, "fixtures");
for (const name of readdirSync(fixturesDir).sort()) {
  const dir = join(fixturesDir, name);
  if (!statSync(dir).isDirectory()) continue;
  const repo = join(dir, "repo");
  const files = walk(repo).map((p) => ({ path: relative(repo, p).split("\\").join("/"), text: readFileSync(p, "utf8") }))
    .sort((a, b) => (a.path < b.path ? -1 : 1));
  // A fixture whose expected files have not been generated yet is skipped rather
  // than crashing the build. Providers are added to the knowledge base before the
  // engine regenerates their expectations, so this directory is routinely mid-flight.
  const findingsPath = join(dir, "expected-findings.json");
  if (!existsSync(findingsPath)) {
    skipped.push(name);
    continue;
  }
  const expectedFindings = JSON.parse(readFileSync(findingsPath, "utf8"));
  const invPath = join(dir, "expected-inventory.json");
  const expectedInventory = existsSync(invPath) ? JSON.parse(readFileSync(invPath, "utf8")) : null;
  samples.push({ name, files, expectedFindings, expectedInventory });
}

// Vendored real repositories. These let the site scan genuine public code with no
// network, which matters on hosts that block outbound requests entirely.
const vendorDir = join(web, "vendor");
const vendored = [];
if (existsSync(vendorDir)) {
  for (const f of readdirSync(vendorDir).sort()) {
    if (!f.endsWith(".json")) continue;
    const d = JSON.parse(readFileSync(join(vendorDir, f), "utf8"));
    vendored.push({ name: d.repo, sha: d.sha, note: d.note, real: true, files: d.files, expectedFindings: null, expectedInventory: null });
  }
}
// Real repositories first: they are the more convincing demo.
const allSamples = [...vendored, ...samples.map((s) => ({ ...s, real: false }))];
writeFileSync(join(outDir, "samples.json"), JSON.stringify(allSamples, null, 2) + "\n");
console.log(`samples: ${vendored.length} real repositories, ${samples.length} fixtures` + (skipped.length ? `, ${skipped.length} fixtures skipped with no expected files (${skipped.join(", ")})` : ""));

// Grammars for the browser. Same versions the Java engine pins.
const grammarsOut = join(web, "public", "grammars");
mkdirSync(grammarsOut, { recursive: true });
const nm = join(web, "node_modules");
const copies = [
  ["web-tree-sitter/web-tree-sitter.wasm", "web-tree-sitter.wasm"],
  ["tree-sitter-java/tree-sitter-java.wasm", "tree-sitter-java.wasm"],
  ["tree-sitter-python/tree-sitter-python.wasm", "tree-sitter-python.wasm"],
  ["tree-sitter-typescript/tree-sitter-typescript.wasm", "tree-sitter-typescript.wasm"],
  ["tree-sitter-typescript/tree-sitter-tsx.wasm", "tree-sitter-tsx.wasm"],
  ["tree-sitter-javascript/tree-sitter-javascript.wasm", "tree-sitter-javascript.wasm"],
  ["tree-sitter-go/tree-sitter-go.wasm", "tree-sitter-go.wasm"],
  ["tree-sitter-ruby/tree-sitter-ruby.wasm", "tree-sitter-ruby.wasm"],
  ["tree-sitter-php/tree-sitter-php.wasm", "tree-sitter-php.wasm"],
  ["tree-sitter-c-sharp/tree-sitter-c_sharp.wasm", "tree-sitter-c_sharp.wasm"],
];
for (const [src, dst] of copies) copyFileSync(join(nm, src), join(grammarsOut, dst));

console.log(`knowledge ${version}: ${providers.length} providers, ${providers.reduce((n, p) => n + p.changes.length, 0)} changes, ${samples.length} samples, ${copies.length} wasm files, ${feeds.length} feed files`);
