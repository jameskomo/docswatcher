import type { InputFile, LiteralRule } from "./types";
import { matchesAny } from "./glob";
import { lineStarts, position, snippetOf } from "./paths";

export interface LiteralHit { rule: LiteralRule; key: string; path: string; line: number; column: number; snippet: string; }

const reCache = new Map<string, RegExp>();
const globCache = new Map<string, RegExp>();

/** Common-subset dialect: JS RegExp with global and indices flags so group 1's start is known. */
export function compileLiteral(rule: LiteralRule): RegExp {
  const cacheKey = rule.id + "|" + rule.pattern;
  let re = reCache.get(cacheKey);
  if (!re) {
    re = new RegExp(rule.pattern, "gd");
    reCache.set(cacheKey, re);
  }
  re.lastIndex = 0;
  return re;
}

export function literalApplies(rule: LiteralRule, path: string): boolean {
  return matchesAny(path, rule.files, globCache) && !matchesAny(path, rule.exclude, globCache);
}

export function scanLiterals(file: InputFile, rules: LiteralRule[]): LiteralHit[] {
  const out: LiteralHit[] = [];
  let starts: number[] | null = null;
  for (const rule of rules) {
    if (!literalApplies(rule, file.path)) continue;
    const re = compileLiteral(rule);
    let m: RegExpExecArray | null;
    while ((m = re.exec(file.text))) {
      if (m[0].length === 0) { re.lastIndex++; continue; }
      const indices = (m as any).indices as Array<[number, number] | undefined> | undefined;
      const groupStart = indices && indices[1] ? indices[1][0] : m.index;
      if (!starts) starts = lineStarts(file.text);
      const { line, column } = position(starts, groupStart);
      const key = rule.key.replace(/\$(\d)/g, (_, d) => m![Number(d)] ?? "");
      out.push({ rule, key, path: file.path, line, column, snippet: snippetOf(file.text, starts, line) });
    }
  }
  return out;
}
