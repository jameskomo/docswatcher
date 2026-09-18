import type { Ecosystem, InputFile, ManifestRule } from "./types";
import { lineStarts, position, snippetOf } from "./paths";

export interface ManifestHit {
  rule: ManifestRule; path: string; line: number; column: number; snippet: string; version: string | null;
}

/** Returns every manifest rule hit in the file, in the order found. */
export function scanManifest(file: InputFile, rules: ManifestRule[]): ManifestHit[] {
  const base = file.path.split("/").pop() ?? "";
  const hits: ManifestHit[] = [];
  const byEco = (e: Ecosystem) => rules.filter((r) => r.ecosystem === e);
  if (base === "package.json") hits.push(...npm(file, byEco("npm")));
  else if (/^requirements[^/]*\.txt$/.test(base)) hits.push(...requirements(file, byEco("pypi")));
  else if (base === "pyproject.toml") hits.push(...pyproject(file, byEco("pypi")));
  else if (base === "pom.xml") hits.push(...maven(file, byEco("maven")));
  else if (base === "go.mod") hits.push(...gomod(file, byEco("go")));
  else if (base === "Gemfile") hits.push(...gemfile(file, byEco("rubygems")));
  return hits;
}

function hit(file: InputFile, starts: number[], rule: ManifestRule, offset: number, version: string | null): ManifestHit {
  const { line, column } = position(starts, offset);
  return { rule, path: file.path, line, column, snippet: snippetOf(file.text, starts, line), version };
}

function npm(file: InputFile, rules: ManifestRule[]): ManifestHit[] {
  if (rules.length === 0) return [];
  let json: any;
  try { json = JSON.parse(file.text); } catch { return []; }
  const starts = lineStarts(file.text);
  const out: ManifestHit[] = [];
  for (const rule of rules) {
    for (const section of ["dependencies", "devDependencies"]) {
      const deps = json?.[section];
      if (!deps || typeof deps !== "object" || !(rule.package in deps)) continue;
      const version = typeof deps[rule.package] === "string" ? deps[rule.package] : null;
      const secIdx = file.text.indexOf(`"${section}"`);
      const needle = `"${rule.package}"`;
      const idx = file.text.indexOf(needle, secIdx >= 0 ? secIdx : 0);
      if (idx < 0) continue;
      out.push(hit(file, starts, rule, idx + 1, version));
      break;
    }
  }
  return out;
}

const REQ_LINE = /^\s*([A-Za-z0-9][A-Za-z0-9._-]*)\s*(?:\[[^\]]*\])?\s*(?:(==|>=|~=|<=|!=|<|>)\s*([^\s;,#]+))?/;

function requirements(file: InputFile, rules: ManifestRule[]): ManifestHit[] {
  if (rules.length === 0) return [];
  const starts = lineStarts(file.text);
  const out: ManifestHit[] = [];
  const seen = new Set<string>();
  file.text.split("\n").forEach((raw, i) => {
    const line = raw.replace(/#.*$/, "");
    if (!line.trim() || line.trim().startsWith("-")) return;
    const m = REQ_LINE.exec(line);
    if (!m) return;
    const name = m[1].toLowerCase();
    for (const rule of rules) {
      if (seen.has(rule.package) || rule.package.toLowerCase() !== name) continue;
      seen.add(rule.package);
      const version = m[2] === "==" || m[2] === ">=" ? m[3] : null;
      out.push(hit(file, starts, rule, starts[i] + line.indexOf(m[1]), version));
    }
  });
  return out;
}

function pyproject(file: InputFile, rules: ManifestRule[]): ManifestHit[] {
  if (rules.length === 0) return [];
  const starts = lineStarts(file.text);
  const out: ManifestHit[] = [];
  const seen = new Set<string>();
  const quoted = /["']([A-Za-z0-9][A-Za-z0-9._-]*)\s*(?:\[[^\]]*\])?\s*(?:(==|>=|~=|<=|!=|<|>)\s*([^"',;\s]+))?[^"']*["']/g;
  const poetry = /^\s*([A-Za-z0-9][A-Za-z0-9._-]*)\s*=\s*(?:["']([^"']+)["']|\{)/;
  file.text.split("\n").forEach((line, i) => {
    let m: RegExpExecArray | null;
    quoted.lastIndex = 0;
    while ((m = quoted.exec(line))) {
      for (const rule of rules) {
        if (seen.has(rule.package) || rule.package.toLowerCase() !== m[1].toLowerCase()) continue;
        seen.add(rule.package);
        out.push(hit(file, starts, rule, starts[i] + m.index + 1, m[2] === "==" || m[2] === ">=" ? m[3] : null));
      }
    }
    const p = poetry.exec(line);
    if (p) for (const rule of rules) {
      if (seen.has(rule.package) || rule.package.toLowerCase() !== p[1].toLowerCase()) continue;
      seen.add(rule.package);
      const v = p[2] ? p[2].replace(/^[\^~>=<]+/, "") : "";
      out.push(hit(file, starts, rule, starts[i] + line.indexOf(p[1]), v || null));
    }
  });
  return out;
}

function maven(file: InputFile, rules: ManifestRule[]): ManifestHit[] {
  if (rules.length === 0) return [];
  const starts = lineStarts(file.text);
  const out: ManifestHit[] = [];
  const seen = new Set<string>();
  const dep = /<dependency>([\s\S]*?)<\/dependency>/g;
  let m: RegExpExecArray | null;
  while ((m = dep.exec(file.text))) {
    const block = m[1];
    const g = /<groupId>\s*([^<\s]+)\s*<\/groupId>/.exec(block);
    const a = /<artifactId>\s*([^<\s]+)\s*<\/artifactId>/.exec(block);
    const v = /<version>\s*([^<\s]+)\s*<\/version>/.exec(block);
    if (!g || !a) continue;
    const coord = `${g[1]}:${a[1]}`;
    for (const rule of rules) {
      if (seen.has(rule.package) || rule.package !== coord) continue;
      seen.add(rule.package);
      const artifactOffset = m.index + "<dependency>".length + a.index + a[0].indexOf(a[1]);
      out.push(hit(file, starts, rule, artifactOffset, v ? v[1] : null));
    }
  }
  return out;
}

function gomod(file: InputFile, rules: ManifestRule[]): ManifestHit[] {
  if (rules.length === 0) return [];
  const starts = lineStarts(file.text);
  const out: ManifestHit[] = [];
  const seen = new Set<string>();
  let inBlock = false;
  file.text.split("\n").forEach((line, i) => {
    const t = line.trim();
    if (t.startsWith("require (")) { inBlock = true; return; }
    if (inBlock && t.startsWith(")")) { inBlock = false; return; }
    let m: RegExpExecArray | null = null;
    if (inBlock) m = /^\s*([^\s]+)\s+([^\s/]+)/.exec(line);
    else m = /^\s*require\s+([^\s(]+)\s+([^\s/]+)/.exec(line);
    if (!m) return;
    for (const rule of rules) {
      if (seen.has(rule.package) || rule.package !== m[1]) continue;
      seen.add(rule.package);
      out.push(hit(file, starts, rule, starts[i] + line.indexOf(m[1]), m[2]));
    }
  });
  return out;
}

function gemfile(file: InputFile, rules: ManifestRule[]): ManifestHit[] {
  if (rules.length === 0) return [];
  const starts = lineStarts(file.text);
  const out: ManifestHit[] = [];
  const seen = new Set<string>();
  const re = /^\s*gem\s+["']([^"']+)["'](?:\s*,\s*["']([^"']+)["'])?/;
  file.text.split("\n").forEach((line, i) => {
    const m = re.exec(line);
    if (!m) return;
    for (const rule of rules) {
      if (seen.has(rule.package) || rule.package !== m[1]) continue;
      seen.add(rule.package);
      out.push(hit(file, starts, rule, starts[i] + line.indexOf(m[1]), m[2] ?? null));
    }
  });
  return out;
}
