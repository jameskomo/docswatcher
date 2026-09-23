// Path exclusion in .gitignore syntax: every .gitignore in the tree, the root .docswatcherignore,
// then patterns given for one run. See docs/18-excluding-paths.md.
//
// Mirrors engine/src/main/java/dev/docswatcher/engine/Ignore.java rule for rule. Both are checked
// against the same cases in engine/src/test/resources/ignore-cases.json, themselves checked
// against git.

export const GITIGNORE = ".gitignore";
export const DOCSWATCHERIGNORE = ".docswatcherignore";

interface Rule { base: string; regex: RegExp; negate: boolean; dirOnly: boolean; nameOnly: boolean }

export interface Ignore { ignored(path: string): boolean }

const NONE: Ignore = { ignored: () => false };

/** A file whose contents configure exclusion: any .gitignore, or .docswatcherignore at the root. */
export function isIgnoreFile(path: string): boolean {
  return path === GITIGNORE || path.endsWith("/" + GITIGNORE) || path === DOCSWATCHERIGNORE;
}

/** Glob to a full-match regex: * and ? stay in one segment, ** crosses them. */
export function globToIgnoreRegex(glob: string): string {
  let out = "";
  const n = glob.length;
  let i = 0;
  while (i < n) {
    const c = glob[i];
    if (i === 0 && glob.startsWith("**/")) { out += "(?:.*/)?"; i += 3; }
    else if (c === "/" && glob.startsWith("/**/", i)) { out += "/(?:.*/)?"; i += 4; }
    else if (c === "/" && glob.startsWith("/**", i) && i + 3 === n) { out += "/.*"; i += 3; }
    else if (c === "*" && glob[i + 1] === "*") { out += ".*"; i += 2; }
    else if (c === "*") { out += "[^/]*"; i++; }
    else if (c === "?") { out += "[^/]"; i++; }
    else if (c === "[" && glob.indexOf("]", i + 1) > i + 1) {
      const close = glob.indexOf("]", i + 1);
      let body = glob.slice(i + 1, close);
      if (body.startsWith("!")) body = "^" + body.slice(1);
      out += "[" + body.replaceAll("\\", "\\\\") + "]";
      i = close + 1;
    } else {
      if ("\\.^$|+(){}[]".includes(c)) out += "\\";
      out += c;
      i++;
    }
  }
  return out;
}

function parse(out: Rule[], base: string, text: string): void {
  for (const raw of text.split(/\r?\n/)) {
    let line = raw.replace(/\s+$/, "");
    if (!line || line.startsWith("#")) continue;
    let negate = false;
    if (line.startsWith("\\#") || line.startsWith("\\!")) line = line.slice(1);
    else if (line.startsWith("!")) { negate = true; line = line.slice(1); }
    const dirOnly = line.endsWith("/");
    line = line.replace(/\/+$/, "");
    if (!line) continue;
    // A slash anywhere but the end anchors the pattern to its file's directory.
    const anchored = line.includes("/");
    if (line.startsWith("/")) line = line.slice(1);
    out.push({ base, regex: new RegExp("^" + globToIgnoreRegex(line) + "$"), negate, dirOnly, nameOnly: !anchored });
  }
}

const dirOf = (path: string) => { const s = path.lastIndexOf("/"); return s < 0 ? "" : path.slice(0, s); };
const depth = (path: string) => path.split("/").length;

/**
 * @param ignoreFiles the ignore files in the tree, as path and text; any other file is disregarded
 * @param exclude patterns for this run, applied last and relative to the root
 */
export function compileIgnore(ignoreFiles: Array<{ path: string; text: string }>, exclude: string[] = []): Ignore {
  const rules: Rule[] = [];
  // Shallower .gitignore files first, so a deeper one can override; then .docswatcherignore;
  // then this run's patterns. The last matching rule decides.
  const gitignores = ignoreFiles
    .filter((f) => f.path === GITIGNORE || f.path.endsWith("/" + GITIGNORE))
    .sort((a, b) => depth(a.path) - depth(b.path) || (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  for (const f of gitignores) parse(rules, dirOf(f.path), f.text);
  const own = ignoreFiles.find((f) => f.path === DOCSWATCHERIGNORE);
  if (own) parse(rules, "", own.text);
  if (exclude.length) parse(rules, "", exclude.join("\n"));
  if (!rules.length) return NONE;

  const decide = (target: string, isDir: boolean): boolean => {
    let excluded = false;
    for (const r of rules) {
      if (r.dirOnly && !isDir) continue;
      let rel: string;
      if (!r.base) rel = target;
      else if (target.startsWith(r.base + "/")) rel = target.slice(r.base.length + 1);
      else continue;
      const subject = r.nameOnly ? rel.slice(rel.lastIndexOf("/") + 1) : rel;
      if (r.regex.test(subject)) excluded = !r.negate;
    }
    return excluded;
  };

  return {
    // As in git, nothing inside an excluded directory comes back.
    ignored(path: string): boolean {
      let slash = path.indexOf("/");
      while (slash >= 0) {
        if (decide(path.slice(0, slash), true)) return true;
        slash = path.indexOf("/", slash + 1);
      }
      return decide(path, false);
    },
  };
}
