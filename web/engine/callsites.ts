import type { CallsiteRule, GrammarLoader, InputFile } from "./types";
import { lineStarts, snippetOf } from "./paths";

export interface CallsiteHit { rule: CallsiteRule; path: string; line: number; column: number; snippet: string; }

/** Thin adapter around web-tree-sitter so the engine has no static import of it. */
export interface TreeSitterApi {
  parse(language: unknown, text: string): { rootNode: unknown; delete?: () => void };
  matches(
    language: unknown, query: string, rootNode: unknown,
  ): Array<{ captures: Array<{ name: string; node: { startPosition: { row: number; column: number } } }> }>;
}

export async function scanCallsites(
  file: InputFile, language: string, rules: CallsiteRule[], loader: GrammarLoader, ts: TreeSitterApi,
): Promise<CallsiteHit[]> {
  if (rules.length === 0) return [];
  const lang = await loader(language as any);
  const tree = ts.parse(lang, file.text);
  const starts = lineStarts(file.text);
  const out: CallsiteHit[] = [];
  try {
    for (const rule of rules) {
      const matches = ts.matches(lang, rule.query, tree.rootNode);
      for (const m of matches) {
        const call = m.captures.find((c) => c.name === "call");
        if (!call) continue;
        const line = call.node.startPosition.row + 1;
        const column = call.node.startPosition.column + 1;
        out.push({ rule, path: file.path, line, column, snippet: snippetOf(file.text, starts, line) });
      }
    }
  } finally { tree.delete?.(); }
  return out;
}
