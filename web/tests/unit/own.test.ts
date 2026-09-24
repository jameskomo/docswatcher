import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { mergeOwn, ownQueryErrors, ownSummary, regexProblems } from "../../engine/own";
import { scan } from "../../engine/scan";
import { match } from "../../engine/matcher";
import { checkApi } from "../../engine/checkApi";
import { createTreeSitter } from "../../engine/treesitter";
import type { InputFile, Knowledge } from "../../engine/types";

const web = join(__dirname, "..", "..");
const knowledge: Knowledge = JSON.parse(readFileSync(join(web, "generated", "knowledge.json"), "utf8"));
// Shared with the Java engine (OwnKnowledgeTest.sharedCases): both report the same problems, in the same words.
const shared = JSON.parse(readFileSync(join(web, "..", "engine", "src", "test", "resources", "own-knowledge-cases.json"), "utf8"));
const today = new Date(`${shared.today}T00:00:00Z`);
const filesOf = (o: Record<string, string>): InputFile[] => Object.entries(o).map(([path, text]) => ({ path, text }));

describe("own records: the shared cases, exactly as the Java engine reports them", () => {
  for (const c of shared.cases) {
    it(c.name, () => {
      const sources = (c.shared ?? []).map((s: string) => ({ label: s, prefix: `${s}/` }));
      const r = mergeOwn(knowledge, filesOf(c.files), today, sources);
      expect(r.errors).toEqual(c.errors);
      expect(r.warnings).toEqual(c.warnings);
      expect(r.providers.map((p) => p.info.id).sort()).toEqual(c.providers);
      expect(r.changes.map((x) => x.id).sort()).toEqual(c.changes);
      if (!r.ok || r.providers.length === 0) expect(r.knowledge).toBe(knowledge);
    });
  }
});

describe("own records in a scan", () => {
  const files = filesOf(shared.cases[0].files);

  it("are added to the knowledge, matched, and never scanned as code", async () => {
    const r = mergeOwn(knowledge, files, today);
    expect(r.ok).toBe(true);
    expect(ownSummary(r)).toBe("Your own API records: 1 provider, 1 change record");
    expect(r.knowledge.version).toBe(knowledge.version);
    const inv = await scan(files, { repo: { host: "local", owner: "local", name: "billing", ref: "x", sha: "0" }, knowledge: r.knowledge, now: () => today });
    expect(inv.contracts.map((c) => c.id)).toEqual(["internal-billing:endpoint:ANY /v1/invoices"]);
    expect(inv.contracts[0].evidence.map((e) => e.path)).toEqual(["src/app.py"]);
    expect(match(inv, r.knowledge, { today }).map((f) => f.change)).toEqual(["internal-billing-v1-sunset"]);
    expect(match(inv, knowledge, { today })).toEqual([]);
  });

  it("answer check_api once they are merged", () => {
    const r = mergeOwn(knowledge, files, today);
    const answer = checkApi(r.knowledge, { value: "https://billing.acme.dev/v1/invoices" }, today);
    expect(answer.verdict).toBe("RETIRING");
    expect(answer.matches.map((m) => m.id)).toEqual(["internal-billing-v1-sunset"]);
    expect(answer.answer).toContain("[Billing service]");
  });

  it("report a query that does not compile, which only the grammars can tell", async () => {
    const nm = join(web, "node_modules");
    const { loader, api } = await createTreeSitter((f) => join(nm, f === "web-tree-sitter.wasm" ? "web-tree-sitter" : "tree-sitter-python", f));
    const bad = mergeOwn(knowledge, filesOf({
      ".docswatcher/providers/internal-x/provider.yaml": "id: internal-x\nname: X\n",
      ".docswatcher/providers/internal-x/detectors.yaml": [
        "manifests: [{ecosystem: pypi, package: x-client}]",
        "callsites:",
        "  - {id: internal-x.py.bad, language: python, kind: sdk_method, requires: x-client, query: '(call (nope)) @call', key: X.bad}",
        "  - {id: internal-x.py.nocall, language: python, kind: sdk_method, requires: x-client, query: '(call) @c', key: X.c}",
      ].join("\n"),
    }), today);
    const errors = await ownQueryErrors(bad.providers, loader, api);
    expect(errors).toHaveLength(2);
    expect(errors[0]).toMatch(/^internal-x\.py\.bad: query does not compile for python: /);
    expect(errors[1]).toBe("internal-x.py.nocall: query has no @call capture");
  });

  it("reject invalid YAML with the file's name", () => {
    const r = mergeOwn(knowledge, filesOf({ ".docswatcher/providers/internal-a/provider.yaml": "id: [unclosed\n" }), today);
    expect(r.errors).toHaveLength(1);
    expect(r.errors[0]).toMatch(/^\.docswatcher\/providers\/internal-a\/provider\.yaml: not valid YAML: /);
    expect(ownSummary(r)).toBe("Your own API records were not used: 1 error");
  });
});

describe("regexProblems mirrors RegexDialect", () => {
  it("names the Java-only constructs and compiles the rest", () => {
    expect(regexProblems("a*+b")).toEqual(["possessive quantifier"]);
    expect(regexProblems("(?i)abc")).toEqual(["inline flags are not allowed"]);
    expect(regexProblems("(?<=x)y")).toEqual(["lookbehind is not allowed"]);
    expect(regexProblems("[unclosed")[0]).toMatch(/^does not compile: /);
    expect(regexProblems("\\b(gpt-[0-9][0-9a-z.-]*)\\b")).toEqual([]);
  });
});
