import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { checkApi } from "../../engine/checkApi";
import type { Knowledge } from "../../engine/types";

const web = join(__dirname, "..", "..");
const knowledge: Knowledge = JSON.parse(readFileSync(join(web, "generated", "knowledge.json"), "utf8"));
// Shared with the CLI's MCP server (McpServerTest.answersTheSharedCasesExactly): both answer alike.
const shared = JSON.parse(readFileSync(join(web, "..", "cli", "src", "test", "resources", "check-api-cases.json"), "utf8"));
const today = new Date(`${shared.today}T00:00:00Z`);

describe("checkApi answers the shared cases exactly as the CLI does", () => {
  for (const c of shared.cases) {
    it(JSON.stringify(c.input), () => {
      const r = checkApi(knowledge, c.input, today);
      expect(r.verdict).toBe(c.verdict);
      expect(r.matches.map((m) => m.id)).toEqual(c.matches);
      expect(r.answer).toBe(c.answer.replace("{version}", knowledge.version));
    });
  }
});

describe("checkApi input errors", () => {
  it("needs a value, a known kind and a known provider", () => {
    expect(() => checkApi(knowledge, { value: " " }, today)).toThrow(/needs a value/);
    expect(() => checkApi(knowledge, { value: "x", kind: "nope" as never }, today)).toThrow(/kind must be/);
    expect(() => checkApi(knowledge, { value: "x", provider: "nope" }, today)).toThrow(/Unknown provider/);
  });
});
