// Syntax highlighting with Shiki: the TextMate grammars VS Code uses, so code here is coloured the way
// a developer's editor colours it.
//
// - The JavaScript regex engine, not WebAssembly, and each grammar is loaded the first time a block
//   needs it, so a page pays only for the languages it shows.
// - One CSS-variables theme. A token's colour is `var(--shiki-token-keyword)` and so on; the values live
//   in main.css, per surface (a dark code block, code on a light card) and per site theme. Switching the
//   site to dark mode is CSS alone, with nothing highlighted twice.
// - Tokens come back as data and are rendered as text by Vue. Highlighted output is never inserted as
//   HTML, which matters because scanned repositories are other people's code.
import type { HighlighterCore, ThemedToken } from "shiki/core";

export type CodeLang =
  | "yaml" | "shellscript" | "json" | "javascript" | "typescript" | "tsx" | "python" | "java" | "go"
  | "ruby" | "php" | "csharp" | "dotenv" | "properties" | "markdown" | "text";

const GRAMMARS: Record<Exclude<CodeLang, "text">, () => Promise<{ default: unknown }>> = {
  yaml: () => import("shiki/langs/yaml.mjs"),
  shellscript: () => import("shiki/langs/shellscript.mjs"),
  json: () => import("shiki/langs/json.mjs"),
  javascript: () => import("shiki/langs/javascript.mjs"),
  typescript: () => import("shiki/langs/typescript.mjs"),
  tsx: () => import("shiki/langs/tsx.mjs"),
  python: () => import("shiki/langs/python.mjs"),
  java: () => import("shiki/langs/java.mjs"),
  go: () => import("shiki/langs/go.mjs"),
  ruby: () => import("shiki/langs/ruby.mjs"),
  php: () => import("shiki/langs/php.mjs"),
  csharp: () => import("shiki/langs/csharp.mjs"),
  dotenv: () => import("shiki/langs/dotenv.mjs"),
  properties: () => import("shiki/langs/properties.mjs"),
  markdown: () => import("shiki/langs/markdown.mjs"),
};

/** A token as the page draws it: its text, and a colour (a CSS variable) when the grammar gave one. */
export interface CodeToken { content: string; color?: string; italic?: boolean }

const THEME = "docswatcher";
let highlighter: Promise<HighlighterCore> | null = null;
const loaded = new Map<CodeLang, Promise<void>>();

function core(): Promise<HighlighterCore> {
  highlighter ??= (async () => {
    const [{ createHighlighterCore, createCssVariablesTheme }, { createJavaScriptRegexEngine }] = await Promise.all([
      import("shiki/core"),
      import("shiki/engine/javascript"),
    ]);
    return createHighlighterCore({
      themes: [createCssVariablesTheme({ name: THEME, variablePrefix: "--shiki-" })],
      langs: [],
      engine: createJavaScriptRegexEngine(),
    });
  })();
  return highlighter;
}

async function ensure(h: HighlighterCore, lang: Exclude<CodeLang, "text">) {
  if (!loaded.has(lang)) {
    loaded.set(lang, GRAMMARS[lang]().then((m) => h.loadLanguage(m.default as never)));
  }
  await loaded.get(lang);
}

/** The code as plain lines, which is what the page shows until (or unless) highlighting arrives. */
export function plainLines(code: string): CodeToken[][] {
  return code.split("\n").map((content) => [{ content }]);
}

/**
 * Splits code into lines of coloured tokens. Any failure (an unknown language, a grammar that does not
 * load) leaves the code plain rather than breaking the block it is in.
 */
export async function highlight(code: string, lang: CodeLang): Promise<CodeToken[][]> {
  if (lang === "text") return plainLines(code);
  try {
    const h = await core();
    await ensure(h, lang);
    const { tokens } = h.codeToTokens(code, { lang, theme: THEME });
    return tokens.map((line: ThemedToken[]) =>
      line.map((t) => ({ content: t.content, color: t.color, italic: ((t.fontStyle ?? 0) & 1) === 1 })),
    );
  } catch {
    return plainLines(code);
  }
}

/** The grammar for a file, by its name, for code from a scanned repository. */
export function langFromPath(path: string): CodeLang {
  const name = path.split("/").pop()!.toLowerCase();
  if (name === ".env" || name.startsWith(".env.")) return "dotenv";
  const ext = name.includes(".") ? name.slice(name.lastIndexOf(".") + 1) : "";
  const byExt: Record<string, CodeLang> = {
    py: "python", java: "java", kt: "text", go: "go", rb: "ruby", php: "php", cs: "csharp",
    ts: "typescript", mts: "typescript", cts: "typescript", tsx: "tsx", jsx: "tsx",
    js: "javascript", mjs: "javascript", cjs: "javascript",
    yml: "yaml", yaml: "yaml", json: "json", properties: "properties", md: "markdown",
  };
  return byExt[ext] ?? "text";
}

/** The grammar for a Markdown fence's info string ("```ts"), for code in an assistant's answer. */
export function langFromFence(info: string): CodeLang {
  const aliases: Record<string, CodeLang> = {
    yaml: "yaml", yml: "yaml", sh: "shellscript", bash: "shellscript", shell: "shellscript", zsh: "shellscript",
    shellscript: "shellscript", json: "json", js: "javascript", javascript: "javascript", ts: "typescript",
    typescript: "typescript", tsx: "tsx", jsx: "tsx", py: "python", python: "python", java: "java", go: "go",
    rb: "ruby", ruby: "ruby", php: "php", cs: "csharp", csharp: "csharp", env: "dotenv", dotenv: "dotenv",
    properties: "properties", md: "markdown", markdown: "markdown",
  };
  return aliases[info.trim().toLowerCase()] ?? "text";
}

/**
 * Splits Markdown text at its fences; a code block keeps its fence's language. split() with one capture
 * group interleaves the parts in fours: prose, opening info string, code, closing info string, prose...
 */
export function splitFences(text: string): { code: boolean; text: string; lang: CodeLang }[] {
  const parts = text.split(/^```([^\n]*)\n?/m);
  const out: { code: boolean; text: string; lang: CodeLang }[] = [];
  for (let i = 0; i < parts.length; i++) {
    if (i % 4 === 0) out.push({ code: false, text: parts[i]!.trim(), lang: "text" });
    else if (i % 4 === 2) out.push({ code: true, text: parts[i]!.replace(/\n$/, ""), lang: langFromFence(parts[i - 1] ?? "") });
  }
  return out.filter((b) => b.text !== "");
}
