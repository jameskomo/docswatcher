import { describe, expect, it } from "vitest";
import { highlight, langFromFence, langFromPath, splitFences } from "../../app/utils/highlight";

describe("splitFences", () => {
  it("keeps prose and code apart, and each block's language", () => {
    const text = "Use the new model:\n```python\nclient.responses.create(model=\"gpt-5\")\n```\nThen run it.\n```sh\npython app.py\n```";
    expect(splitFences(text)).toEqual([
      { code: false, text: "Use the new model:", lang: "text" },
      { code: true, text: 'client.responses.create(model="gpt-5")', lang: "python" },
      { code: false, text: "Then run it.", lang: "text" },
      { code: true, text: "python app.py", lang: "shellscript" },
    ]);
  });
  it("treats a fence without a language as plain text", () => {
    expect(splitFences("```\nplain\n```")).toEqual([{ code: true, text: "plain", lang: "text" }]);
  });
  it("leaves text without fences as one block of prose", () => {
    expect(splitFences("Nothing to show.")).toEqual([{ code: false, text: "Nothing to show.", lang: "text" }]);
  });
});

describe("language from a file or a fence", () => {
  it("knows the scanner's languages by extension", () => {
    expect(langFromPath("examples/assistant-basic/assistant.py")).toBe("python");
    expect(langFromPath("src/Main.java")).toBe("java");
    expect(langFromPath("web/app.tsx")).toBe("tsx");
    expect(langFromPath(".env.production")).toBe("dotenv");
    expect(langFromPath("requirements.txt")).toBe("text");
  });
  it("maps common fence names", () => {
    expect(langFromFence("ts")).toBe("typescript");
    expect(langFromFence(" Bash ")).toBe("shellscript");
    expect(langFromFence("brainfuck")).toBe("text");
  });
});

describe("highlight", () => {
  it("colours tokens with the theme's CSS variables and keeps the text exactly", async () => {
    const code = 'name: API contracts\non: [push, pull_request]';
    const lines = await highlight(code, "yaml");
    expect(lines.map((l) => l.map((t) => t.content).join("")).join("\n")).toBe(code);
    const colours = lines.flat().map((t) => t.color).filter(Boolean);
    expect(colours.length).toBeGreaterThan(0);
    expect(colours.every((c) => c!.startsWith("var(--shiki-"))).toBe(true);
  });
  it("leaves plain text alone", async () => {
    expect(await highlight("a\nb", "text")).toEqual([[{ content: "a" }], [{ content: "b" }]]);
  });
});
