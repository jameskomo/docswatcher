// Runs one knowledge watch. See docs/16-knowledge-watch.md.
//
//   node scripts/watch-sources.mjs --state <dir> [--report <file>] [--today YYYY-MM-DD]
//
// <dir> holds state.json and pages/, and is the checkout of the knowledge-watch branch in CI.
// Writes <file> only when there is something a person should read, and says so on GITHUB_OUTPUT.
// It lives with the web tooling because that is where the Node toolchain and the YAML loader are.
import { readFileSync, writeFileSync, mkdirSync, existsSync, appendFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { loadKnowledge } from "./lib/knowledge.mjs";
import { collectUrls, evaluate, fetchPage, renderReport, slug } from "./lib/watch.mjs";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, a, i, all) => (a.startsWith("--") ? [...pairs, [a.slice(2), all[i + 1]]] : pairs), []),
);
if (!args.state) {
  console.error("usage: watch-sources.mjs --state <dir> [--report <file>] [--today YYYY-MM-DD]");
  process.exit(2);
}
const today = args.today ?? new Date().toISOString().slice(0, 10);
const here = dirname(fileURLToPath(import.meta.url));
const knowledge = loadKnowledge(args.knowledge ?? join(here, "..", "..", "knowledge"));

const stateFile = join(args.state, "state.json");
const pagesDir = join(args.state, "pages");
const state = existsSync(stateFile) ? JSON.parse(readFileSync(stateFile, "utf8")) : null;
const urls = collectUrls(knowledge);

const previousText = {};
for (const { url } of urls) {
  const f = join(pagesDir, slug(url));
  if (existsSync(f)) previousText[url] = readFileSync(f, "utf8");
}

const pages = {};
for (const { url } of urls) {
  pages[url] = await fetchPage(url);
  const p = pages[url];
  console.log(`${p.ok ? `${p.text.length.toString().padStart(7)} chars` : `  ${p.error}`.padEnd(13)}  ${url}`);
  await new Promise((r) => setTimeout(r, 500)); // polite to the providers' servers
}

const result = evaluate({ knowledge, urls, pages, state, previousText, today });

mkdirSync(pagesDir, { recursive: true });
for (const [url, text] of Object.entries(result.snapshots)) writeFileSync(join(pagesDir, slug(url)), text + "\n");
writeFileSync(stateFile, JSON.stringify(result.state, null, 2) + "\n");

const r = result.report;
console.log(result.first
  ? `\nBaseline recorded for ${urls.length} sources. Nothing is reported on a first run.`
  : `\n${r.changed.length} changed with news, ${r.quietlyChanged.length} changed quietly, ${r.failing.length} failing, `
    + `${r.unreadable.length} newly unreadable, ${r.ageing.length} newly ageing records, ${r.expiring.length} newly due to expire.`);

if (result.worthAnIssue && args.report) writeFileSync(args.report, renderReport(r, { today }));
if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `report=${result.worthAnIssue}\n`);
