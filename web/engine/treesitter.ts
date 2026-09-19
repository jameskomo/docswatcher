import type { GrammarLoader } from "./types";
import type { TreeSitterApi } from "./callsites";

export const GRAMMAR_FILES: Record<string, string> = {
  java: "tree-sitter-java.wasm",
  python: "tree-sitter-python.wasm",
  typescript: "tree-sitter-typescript.wasm",
  tsx: "tree-sitter-tsx.wasm",
  javascript: "tree-sitter-javascript.wasm",
  go: "tree-sitter-go.wasm",
};

/**
 * Builds the tree-sitter adapter and a lazy grammar loader.
 * `locate(file)` returns where a wasm file lives: a URL in the browser, a filesystem path in Node.
 */
export async function createTreeSitter(locate: (file: string) => string | URL | Uint8Array | Promise<string | URL | Uint8Array>) {
  if (typeof globalThis !== "undefined" && (globalThis as any).process && !(globalThis as any).process.versions) {
    (globalThis as any).process.versions = {};
  }
  const mod: any = await import("web-tree-sitter");
  const { Parser, Language, Query } = mod.default || mod;
  await Parser.init({ locateFile: () => String(locate("web-tree-sitter.wasm")) });
  const languages = new Map<string, Promise<any>>();
  const queries = new Map<string, any>();
  const parser = new Parser();

  const loader: GrammarLoader = (language) => {
    const file = GRAMMAR_FILES[language];
    if (!file) throw new Error(`no grammar for ${language}`);
    let p = languages.get(language);
    if (!p) {
      p = Promise.resolve(locate(file)).then(async (src) => {
        if (typeof globalThis !== "undefined" && (globalThis as any).process && !(globalThis as any).process.versions) {
          (globalThis as any).process.versions = {};
        }
        if (typeof src === "string" && typeof fetch !== "undefined" && !src.startsWith("file:")) {
          try {
            const resp = await fetch(src);
            if (resp.ok) {
              const buf = await resp.arrayBuffer();
              return Language.load(new Uint8Array(buf));
            }
          } catch {
            // fall back to default loader
          }
        }
        return Language.load(src);
      });
      languages.set(language, p);
    }
    return p;
  };

  const api: TreeSitterApi = {
    parse(language, text) {
      parser.setLanguage(language);
      return parser.parse(text);
    },
    matches(language, query, rootNode) {
      const key = (language as any).name + "" + query;
      let q = queries.get(key);
      if (!q) { q = new Query(language, query); queries.set(key, q); }
      return q.matches(rootNode);
    },
  };
  return { loader, api };
}
