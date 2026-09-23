// docs/18-excluding-paths.md. The Java engine runs the same cases in IgnoreTest.
import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { compileIgnore } from "../../engine/ignore";
import { scan } from "../../engine";
import type { Knowledge } from "../../engine";

const shared = JSON.parse(readFileSync(join(__dirname, "..", "..", "..", "engine", "src", "test", "resources", "ignore-cases.json"), "utf8"));
const knowledge: Knowledge = JSON.parse(readFileSync(join(__dirname, "..", "..", "generated", "knowledge.json"), "utf8"));
const repo = { host: "fixture", owner: "docswatcher", name: "t", ref: "fixture", sha: "0000000" };

describe("the cases shared with the Java engine", () => {
  for (const c of shared.cases) {
    it(c.name, () => {
      const files = Object.entries(c.ignoreFiles as Record<string, string>).map(([path, text]) => ({ path, text }));
      const ignore = compileIgnore(files, c.exclude ?? []);
      for (const [path, want] of Object.entries(c.expect as Record<string, boolean>)) {
        expect(ignore.ignored(path), path).toBe(want);
      }
    });
  }
});

describe("scanning", () => {
  const files = [
    { path: "requirements.txt", text: "openai==1.0.0\n" },
    { path: "app.py", text: 'client.chat.completions.create(model="gpt-4-turbo")\n' },
    { path: "samples/old.py", text: 'client.images.generate(model="dall-e-2")\n' },
    { path: ".docswatcherignore", text: "samples/\n" },
  ];
  const keys = async (fs: typeof files, exclude?: string[]) =>
    (await scan(fs, { repo, knowledge, exclude })).contracts.map((c) => c.key);

  it("never reads an excluded file, and counts it as skipped", async () => {
    const inv = await scan(files, { repo, knowledge });
    expect(inv.contracts.map((c) => c.key)).toContain("gpt-4-turbo");
    expect(inv.contracts.map((c) => c.key)).not.toContain("dall-e-2");
    expect(inv.stats.filesSkipped).toBe(1);
  });

  it("finds it again without the ignore file, and loses it to --exclude", async () => {
    const withoutIgnore = files.filter((f) => f.path !== ".docswatcherignore");
    expect(await keys(withoutIgnore)).toContain("dall-e-2");
    expect(await keys(withoutIgnore, ["app.py"])).not.toContain("gpt-4-turbo");
  });
});
