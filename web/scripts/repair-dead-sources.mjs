// Replaces dead source URLs with their Internet Archive snapshots. See docs/16-knowledge-watch.md,
// "Repair dead sources".
//
//   node scripts/repair-dead-sources.mjs --dead <file> [--knowledge <dir>] [--write] [--body <file>] [--issue <file>]
//
// <file> lists the URLs that answered 404 or 410, one per line. Without --write it only reports.
// --body gets the pull request text when something was repaired; --issue gets the list of URLs no
// snapshot exists for. Says `repaired=<n>` and `unresolved=<n>` on GITHUB_OUTPUT.
import { readFileSync, writeFileSync, readdirSync, existsSync, appendFileSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { availabilityQuery, cites, observedFor, replaceUrl, slashVariant, snapshotUrl } from "./lib/sources.mjs";

const args = {};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  if (!argv[i].startsWith("--")) continue;
  const next = argv[i + 1];
  args[argv[i].slice(2)] = next === undefined || next.startsWith("--") ? true : (i++, next);
}
if (typeof args.dead !== "string") throw new Error("missing --dead <file>");
const here = dirname(fileURLToPath(import.meta.url));
const knowledgeDir = typeof args.knowledge === "string" ? args.knowledge : join(here, "..", "..", "knowledge");
const repoRoot = join(knowledgeDir, "..");
const UA = "Mozilla/5.0 (compatible; DocsWatcher link check; +https://github.com/jameskomo/docswatcher)";
const today = new Date().toISOString().slice(0, 10);

const dead = [...new Set(readFileSync(args.dead, "utf8").split("\n").map((l) => l.trim()).filter(Boolean))];

const records = [];
const providersDir = join(knowledgeDir, "providers");
for (const provider of readdirSync(providersDir).sort()) {
  const changesDir = join(providersDir, provider, "changes");
  if (!existsSync(changesDir)) continue;
  for (const f of readdirSync(changesDir).sort()) {
    if (f.endsWith(".yaml")) records.push(join(changesDir, f));
  }
}

const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** The archive's answer, tried three times: it is slow and occasionally answers with nothing. */
async function snapshotFor(url, observed) {
  for (const wait of [0, 5_000, 15_000]) {
    await pause(wait);
    try {
      const res = await fetch(availabilityQuery(url, observed), { headers: { "user-agent": UA }, signal: AbortSignal.timeout(30_000) });
      if (!res.ok) continue;
      const found = snapshotUrl(await res.json());
      if (found) return found;
    } catch { /* tried again below */ }
  }
  return null;
}

/** A live page the provider may have moved this one to, or null. Only a hint for the reviewer. */
async function movedTo(url) {
  const variant = slashVariant(url);
  if (!variant) return null;
  try {
    const res = await fetch(variant, { redirect: "follow", headers: { "user-agent": UA }, signal: AbortSignal.timeout(30_000) });
    return res.ok && res.url !== url ? res.url : null;
  } catch {
    return null;
  }
}

const repaired = [];
const unresolved = [];
for (const url of dead) {
  const citing = records.filter((file) => cites(readFileSync(file, "utf8"), url));
  if (!citing.length) continue;
  const observed = citing.map((file) => observedFor(readFileSync(file, "utf8"), url)).find(Boolean) ?? null;
  const [snapshot, moved] = await Promise.all([snapshotFor(url, observed), movedTo(url)]);
  const paths = citing.map((file) => relative(repoRoot, file));
  if (!snapshot) {
    unresolved.push({ url, moved, paths });
    console.log(`no snapshot  ${url}`);
    continue;
  }
  if (args.write) {
    for (const file of citing) writeFileSync(file, replaceUrl(readFileSync(file, "utf8"), url, snapshot).text);
  }
  repaired.push({ url, snapshot, moved, paths });
  console.log(`${args.write ? "replaced" : "would replace"}  ${url}  ->  ${snapshot}`);
}

const entry = (d) => [
  `- ${d.url}`,
  ...(d.snapshot ? [`  - now cites the archive copy: ${d.snapshot}`] : []),
  ...(d.moved ? [`  - the provider may have moved it here: ${d.moved}. If that page still says what the note quotes, prefer it: edit the url and the note on this branch.`] : []),
  ...d.paths.map((p) => `  - \`${p}\``),
].join("\n");

if (typeof args.body === "string" && repaired.length) {
  writeFileSync(args.body, [
    `${repaired.length} source page${repaired.length === 1 ? "" : "s"} the knowledge base cites answered 404 or 410 three times on ${today}. `
      + "Each is replaced by the Internet Archive snapshot closest to the day a person last confirmed it, so every note still quotes the page it came from.",
    "",
    ...repaired.map(entry),
    ...(unresolved.length ? ["", "No snapshot exists for these, so they are unchanged and listed in the \"Knowledge link check\" issue:", "", ...unresolved.map(entry)] : []),
    "",
    "Only `url:` lines change. Opened by `.github/workflows/link-check.yml`, which rebuilds this branch every Monday. "
      + "GitHub does not run CI on a pull request opened by a workflow until you press *Approve and run* on it; the change is safe to merge either way.",
    "",
  ].join("\n"));
}
if (typeof args.issue === "string" && unresolved.length) {
  writeFileSync(args.issue, [
    `These source pages answered 404 or 410 three times on ${today}, and the Internet Archive has no copy of them. `
      + "Replace each with the page the provider moved it to, or with another source for the same fact. If the provider retracted the change, set the record to `status: withdrawn`.",
    "",
    ...unresolved.map(entry),
    "",
    "Rewritten by `.github/workflows/link-check.yml` every Monday while any remain.",
    "",
  ].join("\n"));
}
console.log(`${dead.length} dead: ${repaired.length} repaired, ${unresolved.length} without a snapshot.`);
if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `repaired=${repaired.length}\nunresolved=${unresolved.length}\n`);
