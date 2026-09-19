import { describe, it, expect, beforeAll } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { scan, match, toJson, createTreeSitter, globToRegExp, isDocOrTestPath, scanManifest, scanLiterals, affectsMatches, daysBetween } from "../../engine";
import type { Knowledge, LiteralRule, Contract } from "../../engine";

const web = join(__dirname, "..", "..");
const knowledge: Knowledge = JSON.parse(readFileSync(join(web, "generated", "knowledge.json"), "utf8"));
const samples = JSON.parse(readFileSync(join(web, "generated", "samples.json"), "utf8"));
const today = new Date(Date.UTC(2026, 8, 18));
const repo = { host: "fixture", owner: "docswatcher", name: "t", ref: "fixture", sha: "0000000" };

let grammars: Awaited<ReturnType<typeof createTreeSitter>>;
beforeAll(async () => {
  const nm = join(web, "node_modules");
  const map: Record<string, string> = {
    "web-tree-sitter.wasm": "web-tree-sitter/web-tree-sitter.wasm",
    "tree-sitter-java.wasm": "tree-sitter-java/tree-sitter-java.wasm",
    "tree-sitter-python.wasm": "tree-sitter-python/tree-sitter-python.wasm",
    "tree-sitter-typescript.wasm": "tree-sitter-typescript/tree-sitter-typescript.wasm",
    "tree-sitter-tsx.wasm": "tree-sitter-typescript/tree-sitter-tsx.wasm",
    "tree-sitter-javascript.wasm": "tree-sitter-javascript/tree-sitter-javascript.wasm",
    "tree-sitter-go.wasm": "tree-sitter-go/tree-sitter-go.wasm",
  };
  grammars = await createTreeSitter((f) => join(nm, map[f]));
});

describe("glob", () => {
  it("matches root files and nested files with **/", () => {
    const re = globToRegExp("**/*.{java,py}");
    expect(re.test("Main.java")).toBe(true);
    expect(re.test("a/b/c.py")).toBe(true);
    expect(re.test("a/b/c.ts")).toBe(false);
  });
  it("matches everything with **/*", () => {
    expect(globToRegExp("**/*").test("README.md")).toBe(true);
    expect(globToRegExp("**/node_modules/**").test("x/node_modules/y/z.js")).toBe(true);
  });
});

describe("doc and test paths", () => {
  it("classifies per the schema", () => {
    expect(isDocOrTestPath("README.md")).toBe(true);
    expect(isDocOrTestPath("src/test/java/Foo.java")).toBe(true);
    expect(isDocOrTestPath("src/FooTest.java")).toBe(true);
    expect(isDocOrTestPath("a/b.spec.ts")).toBe(true);
    expect(isDocOrTestPath("src/main.py")).toBe(false);
    expect(isDocOrTestPath("testing/main.py")).toBe(false);
  });
});

describe("manifests", () => {
  it("reads package.json with version as written", () => {
    const hits = scanManifest({ path: "package.json", text: '{\n  "dependencies": {\n    "stripe": "^17.3.0"\n  }\n}\n' }, [{ ecosystem: "npm", package: "stripe" }]);
    expect(hits).toHaveLength(1);
    expect(hits[0]).toMatchObject({ line: 3, column: 6, version: "^17.3.0" });
  });
  it("reads requirements.txt case-insensitively and pins", () => {
    const hits = scanManifest({ path: "requirements.txt", text: "OpenAI==1.51.0\n" }, [{ ecosystem: "pypi", package: "openai" }]);
    expect(hits[0]).toMatchObject({ line: 1, column: 1, version: "1.51.0" });
  });
  it("reads pom.xml coordinates", () => {
    const text = "<project><dependencies><dependency>\n<groupId>com.stripe</groupId>\n<artifactId>stripe-java</artifactId>\n<version>29.0.0</version>\n</dependency></dependencies></project>";
    const hits = scanManifest({ path: "pom.xml", text }, [{ ecosystem: "maven", package: "com.stripe:stripe-java" }]);
    expect(hits[0]).toMatchObject({ line: 3, column: 13, version: "29.0.0" });
  });
  it("reads go.mod blocks and Gemfile", () => {
    expect(scanManifest({ path: "go.mod", text: "module x\nrequire (\n\tgithub.com/stripe/stripe-go v79.0.0\n)\n" }, [{ ecosystem: "go", package: "github.com/stripe/stripe-go" }])[0])
      .toMatchObject({ line: 3, column: 2, version: "v79.0.0" });
    expect(scanManifest({ path: "Gemfile", text: 'gem "shopify_api", "14.0.0"\n' }, [{ ecosystem: "rubygems", package: "shopify_api" }])[0])
      .toMatchObject({ line: 1, column: 6, version: "14.0.0" });
  });
});

describe("literals", () => {
  const rule: LiteralRule = { id: "t.model", kind: "model", files: ["**/*"], pattern: 'model: ([a-z0-9-]+)', key: "$1", confidence: "medium" };
  it("reports group-1 column and trimmed snippet", () => {
    const hits = scanLiterals({ path: "c.yaml", text: "llm:\n   model: gpt-4-turbo\n" }, [rule]);
    expect(hits[0]).toMatchObject({ key: "gpt-4-turbo", line: 2, column: 11, snippet: "model: gpt-4-turbo" });
  });
  it("respects exclude globs", () => {
    expect(scanLiterals({ path: "node_modules/x/c.yaml", text: "model: a\n" }, [{ ...rule, exclude: ["**/node_modules/**"] }])).toHaveLength(0);
  });
});

describe("matcher", () => {
  const c = (kind: any, key: string): Contract => ({ id: `p:${kind}:${key}`, provider: "p", kind, key, confidence: "high", evidence: [] });
  it("matches ANY and method endpoints per the schema", () => {
    expect(affectsMatches({ kind: "endpoint", match: "ANY /v1/x" }, c("endpoint", "POST /v1/x"))).toBe(true);
    expect(affectsMatches({ kind: "endpoint", match: "POST /v1/x" }, c("endpoint", "ANY /v1/x"))).toBe(true);
    expect(affectsMatches({ kind: "endpoint", match: "POST /v1/x" }, c("endpoint", "GET /v1/x"))).toBe(false);
    expect(affectsMatches({ kind: "endpoint", match: "POST /v1/x/*" }, c("endpoint", "POST /v1/x/y"))).toBe(true);
  });
  it("compares api versions", () => {
    expect(affectsMatches({ kind: "api_version", match: "< 2022-11-15" }, c("api_version", "2020-08-27"))).toBe(true);
    expect(affectsMatches({ kind: "api_version", match: "< 2022-11-15" }, c("api_version", "2023-01-01"))).toBe(false);
  });
  it("computes days remaining, negative when past", () => {
    expect(daysBetween(today, "2026-10-23")).toBe(35);
    expect(daysBetween(today, "2026-08-26")).toBe(-23);
  });
});

describe("fixtures end to end", () => {
  // Only knowledge base fixtures carry expected files. The vendored real
  // repositories are demo data with no pinned expectations; they are covered
  // by the browser tests instead.
  for (const s of samples.filter((x: any) => !x.real)) {
    it(`${s.name} yields the expected findings`, async () => {
      const inv = await scan(s.files, { repo: { ...repo, name: s.name }, knowledge, grammars, now: () => today });
      const findings = match(inv, knowledge, { today }).map((f) => ({ contract: f.contract, change: f.change }));
      const norm = (a: any[]) => [...a].sort((x, y) => (x.contract + x.change < y.contract + y.change ? -1 : 1));
      expect(norm(findings)).toEqual(norm(s.expectedFindings));
      if (s.expectedInventory) expect(toJson(inv.contracts)).toBe(toJson(s.expectedInventory));
    });
  }
  it("every vendored real repository scans without throwing", async () => {
    const real = samples.filter((x: any) => x.real);
    expect(real.length).toBeGreaterThan(0);
    for (const s of real) {
      const inv = await scan(s.files, { repo: { ...repo, name: s.name }, knowledge, grammars, now: () => today });
      expect(inv.schemaVersion).toBe("1");
      expect(inv.contracts.length).toBeGreaterThan(0);
      match(inv, knowledge, { today });
    }
  });

  it("hides low-confidence contracts unless asked", async () => {
    const s = samples.find((x: any) => x.name === "anthropic-negative-readme-only");
    const inv = await scan(s.files, { repo, knowledge, grammars, now: () => today });
    expect(inv.contracts[0].confidence).toBe("low");
    expect(match(inv, knowledge, { today })).toHaveLength(0);
    expect(match(inv, knowledge, { today, includeLow: true })).toHaveLength(1);
  });
  it("serializes with two-space indent and trailing newline", async () => {
    const s = samples[0];
    const inv = await scan(s.files, { repo, knowledge, grammars, now: () => today });
    const json = toJson(inv);
    expect(json.endsWith("}\n")).toBe(true);
    expect(json.startsWith('{\n  "schemaVersion": "1",\n  "repo": {')).toBe(true);
  });
});
