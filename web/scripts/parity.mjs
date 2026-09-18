// Runs the TypeScript engine over every fixture and compares to expected-inventory.json / expected-findings.json.
// Run with: node --experimental-strip-types scripts/parity.mjs   (npm run parity does this)
import { readFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, relative, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { scan, match, toJson, createTreeSitter } from "../engine/index.ts";

const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, "..");
const knowledge = JSON.parse(readFileSync(join(web, "generated", "knowledge.json"), "utf8"));
const fixturesDir = join(web, "..", "knowledge", "fixtures");
const today = new Date(Date.UTC(2026, 8, 18));

const nm = join(web, "node_modules");
const wasmPath = (file) => {
  const map = {
    "web-tree-sitter.wasm": "web-tree-sitter/web-tree-sitter.wasm",
    "tree-sitter-java.wasm": "tree-sitter-java/tree-sitter-java.wasm",
    "tree-sitter-python.wasm": "tree-sitter-python/tree-sitter-python.wasm",
    "tree-sitter-typescript.wasm": "tree-sitter-typescript/tree-sitter-typescript.wasm",
    "tree-sitter-tsx.wasm": "tree-sitter-typescript/tree-sitter-tsx.wasm",
    "tree-sitter-javascript.wasm": "tree-sitter-javascript/tree-sitter-javascript.wasm",
    "tree-sitter-go.wasm": "tree-sitter-go/tree-sitter-go.wasm",
  };
  return join(nm, map[file]);
};
const grammars = await createTreeSitter(wasmPath);

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p)); else out.push(p);
  }
  return out;
}

const writeExpected = process.argv.includes("--write-expected");
const only = process.argv.find((a) => a.startsWith("--only="))?.slice(7);
const rows = [];
let failed = 0;
for (const name of readdirSync(fixturesDir).sort()) {
  if (only && name !== only) continue;
  const dir = join(fixturesDir, name);
  if (!statSync(dir).isDirectory()) continue;
  const repo = join(dir, "repo");
  const files = walk(repo).map((p) => ({ path: relative(repo, p).split("\\").join("/"), text: readFileSync(p, "utf8") }))
    .sort((a, b) => (a.path < b.path ? -1 : 1));
  const inv = await scan(files, {
    repo: { host: "fixture", owner: "docwatcher", name, ref: "fixture", sha: "0000000" },
    knowledge, grammars, now: () => today,
  });
  const invPath = join(dir, "expected-inventory.json");
  let invResult = "SKIP";
  const actualInv = toJson(inv.contracts);
  if (writeExpected) {
    (await import("node:fs")).writeFileSync(invPath, actualInv);
    invResult = "WROTE";
  } else if (existsSync(invPath)) {
    const expected = readFileSync(invPath, "utf8");
    invResult = expected === actualInv ? "PASS" : "FAIL";
    if (invResult === "FAIL") {
      failed++;
      console.error(`\n--- ${name}: inventory mismatch ---`);
      printDiff(expected, actualInv);
    }
  }
  const findings = match(inv, knowledge, { today });
  const actualF = findings.map((f) => ({ contract: f.contract, change: f.change }))
    .sort((a, b) => (a.contract === b.contract ? (a.change < b.change ? -1 : a.change > b.change ? 1 : 0) : a.contract < b.contract ? -1 : 1));
  const expectedF = JSON.parse(readFileSync(join(dir, "expected-findings.json"), "utf8"))
    .sort((a, b) => (a.contract === b.contract ? (a.change < b.change ? -1 : a.change > b.change ? 1 : 0) : a.contract < b.contract ? -1 : 1));
  const fResult = JSON.stringify(actualF) === JSON.stringify(expectedF) ? "PASS" : "FAIL";
  if (fResult === "FAIL") {
    failed++;
    console.error(`\n--- ${name}: findings mismatch ---`);
    printDiff(JSON.stringify(expectedF, null, 2), JSON.stringify(actualF, null, 2));
  }
  rows.push({ fixture: name, contracts: inv.contracts.length, inventory: invResult, findings: fResult, count: findings.length });
}

console.log("\nfixture".padEnd(40) + "contracts  inventory  findings");
for (const r of rows) console.log(r.fixture.padEnd(40) + String(r.contracts).padEnd(11) + r.inventory.padEnd(11) + `${r.findings} (${r.count})`);
console.log(failed ? `\n${failed} check(s) failed` : "\nall checks passed");
process.exit(failed ? 1 : 0);

function printDiff(expected, actual) {
  const e = expected.split("\n"), a = actual.split("\n");
  const n = Math.max(e.length, a.length);
  let shown = 0;
  for (let i = 0; i < n && shown < 40; i++) {
    if (e[i] !== a[i]) { console.error(`  line ${i + 1}\n    expected: ${e[i] ?? "<eof>"}\n    actual:   ${a[i] ?? "<eof>"}`); shown++; }
  }
}
