// Reads ../knowledge/** into web/generated/{knowledge,samples}.json and copies wasm grammars into public/grammars.
import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync, existsSync, copyFileSync } from "node:fs";
import { join, relative, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import YAML from "yaml";

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

const versionFile = join(knowledgeDir, "VERSION");
const version = existsSync(versionFile) ? readFileSync(versionFile, "utf8").trim() : "0.0.0-dev";

const providers = [];
for (const id of readdirSync(join(knowledgeDir, "providers")).sort()) {
  const dir = join(knowledgeDir, "providers", id);
  if (!statSync(dir).isDirectory()) continue;
  const info = YAML.parse(readFileSync(join(dir, "provider.yaml"), "utf8"));
  const detectors = YAML.parse(readFileSync(join(dir, "detectors.yaml"), "utf8"));
  detectors.manifests ??= []; detectors.literals ??= []; detectors.callsites ??= [];
  const changes = [];
  const changesDir = join(dir, "changes");
  if (existsSync(changesDir)) {
    for (const f of readdirSync(changesDir).sort()) {
      if (!f.endsWith(".yaml")) continue;
      const rec = YAML.parse(readFileSync(join(changesDir, f), "utf8"));
      for (const k of ["announced", "effective"]) if (rec[k] instanceof Date) rec[k] = rec[k].toISOString().slice(0, 10);
      for (const s of rec.sources ?? []) if (s.observed instanceof Date) s.observed = s.observed.toISOString().slice(0, 10);
      if (rec.summary) rec.summary = String(rec.summary).trim();
      if (rec.migration?.notes) rec.migration.notes = String(rec.migration.notes).trim();
      changes.push(rec);
    }
  }
  providers.push({ info, detectors, changes });
}
writeFileSync(join(outDir, "knowledge.json"), JSON.stringify({ version, providers }, null, 2) + "\n");

const samples = [];
const fixturesDir = join(knowledgeDir, "fixtures");
for (const name of readdirSync(fixturesDir).sort()) {
  const dir = join(fixturesDir, name);
  if (!statSync(dir).isDirectory()) continue;
  const repo = join(dir, "repo");
  const files = walk(repo).map((p) => ({ path: relative(repo, p).split("\\").join("/"), text: readFileSync(p, "utf8") }))
    .sort((a, b) => (a.path < b.path ? -1 : 1));
  const expectedFindings = JSON.parse(readFileSync(join(dir, "expected-findings.json"), "utf8"));
  const invPath = join(dir, "expected-inventory.json");
  const expectedInventory = existsSync(invPath) ? JSON.parse(readFileSync(invPath, "utf8")) : null;
  samples.push({ name, files, expectedFindings, expectedInventory });
}
writeFileSync(join(outDir, "samples.json"), JSON.stringify(samples, null, 2) + "\n");

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
];
for (const [src, dst] of copies) copyFileSync(join(nm, src), join(grammarsOut, dst));

console.log(`knowledge ${version}: ${providers.length} providers, ${providers.reduce((n, p) => n + p.changes.length, 0)} changes, ${samples.length} samples, ${copies.length} wasm files`);
