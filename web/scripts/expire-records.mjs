// Sets `status: expired` on every active change record whose effective date has passed.
// See docs/16-knowledge-watch.md.
//
//   node scripts/expire-records.mjs [--knowledge <dir>] [--today YYYY-MM-DD] [--write] [--body <file>]
//
// Without --write it only lists them. With --body it writes the pull request text there. Says how
// many it changed on GITHUB_OUTPUT as `expired=<n>`.
import { readFileSync, writeFileSync, readdirSync, existsSync, statSync, appendFileSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";
import YAML from "yaml";
import { dueForExpiry, setExpired } from "./lib/expire.mjs";

const args = {};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  if (!argv[i].startsWith("--")) continue;
  const next = argv[i + 1];
  args[argv[i].slice(2)] = next === undefined || next.startsWith("--") ? true : (i++, next);
}
const today = typeof args.today === "string" ? args.today : new Date().toISOString().slice(0, 10);
const here = dirname(fileURLToPath(import.meta.url));
const knowledgeDir = typeof args.knowledge === "string" ? args.knowledge : join(here, "..", "..", "knowledge");
const repoRoot = join(knowledgeDir, "..");

const due = [];
const providersDir = join(knowledgeDir, "providers");
for (const provider of readdirSync(providersDir).sort()) {
  const changesDir = join(providersDir, provider, "changes");
  if (!statSync(join(providersDir, provider)).isDirectory() || !existsSync(changesDir)) continue;
  for (const f of readdirSync(changesDir).sort()) {
    if (!f.endsWith(".yaml")) continue;
    const file = join(changesDir, f);
    const text = readFileSync(file, "utf8");
    const record = YAML.parse(text);
    if (!dueForExpiry(record, today)) continue;
    const effective = record.effective instanceof Date ? record.effective.toISOString().slice(0, 10) : record.effective;
    due.push({ id: record.id, effective, path: relative(repoRoot, file) });
    if (args.write) writeFileSync(file, setExpired(text));
  }
}

for (const d of due) console.log(`${args.write ? "expired" : "due"}  ${d.effective}  ${d.id}`);
console.log(due.length ? `${due.length} record${due.length === 1 ? "" : "s"} past their effective date.` : "No active record is past its effective date.");

if (typeof args.body === "string" && due.length) {
  writeFileSync(args.body, [
    `Sets \`status: expired\` on ${due.length} change record${due.length === 1 ? "" : "s"} whose effective date has passed, as of ${today}.`,
    "`docswatcher validate` fails on an active record after its date, so CI on `main` stays red until this is merged.",
    "",
    ...due.map((d) => `- \`${d.id}\`, effective ${d.effective} (\`${d.path}\`)`),
    "",
    "Each file changes by one line. Expired records still produce findings; only the label on them changes. "
      + "If a provider moved a date instead, close this and correct the record.",
    "",
    "Opened by `.github/workflows/knowledge-watch.yml`, which refreshes this branch daily. CI does not run automatically on "
      + "pull requests opened by a workflow: push a commit to this branch, or close and reopen it, to run it.",
    "",
  ].join("\n"));
}
if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `expired=${due.length}\n`);
