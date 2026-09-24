// Rewrites cli/src/test/resources/check-api-cases.json from the TypeScript checkApi, keeping
// every case's input and the file's "today". Run after the knowledge base changes the answers
// (a new record, a new record count, a newer observed date), then review the diff: the Java
// McpServerTest and web/tests/unit/checkApi.test.ts both hold their engine to this file.
// Usage, from web/: npm run bundle && npx tsx scripts/regen-check-api-cases.mts
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { checkApi } from "../engine/checkApi";
import type { Knowledge } from "../engine/types";

const web = join(import.meta.dirname, "..");
const file = join(web, "..", "cli", "src", "test", "resources", "check-api-cases.json");
const knowledge: Knowledge = JSON.parse(readFileSync(join(web, "generated", "knowledge.json"), "utf8"));
const shared = JSON.parse(readFileSync(file, "utf8"));
const today = new Date(`${shared.today}T00:00:00Z`);

for (const c of shared.cases) {
  const r = checkApi(knowledge, c.input, today);
  c.verdict = r.verdict;
  c.matches = r.matches.map((m) => m.id);
  c.answer = r.answer.split(knowledge.version).join("{version}");
}
writeFileSync(file, JSON.stringify(shared, null, 2) + "\n");
console.log(`${shared.cases.length} cases rewritten against knowledge ${knowledge.version}`);
