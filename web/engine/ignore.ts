// Path exclusion in .gitignore syntax: every .gitignore in the tree, the root .docswatcherignore,
// then patterns given for one run. See docs/18-excluding-paths.md.
//
// Mirrors engine/src/main/java/dev/docswatcher/engine/Ignore.java rule for rule. Both are checked
// against the same cases in engine/src/test/resources/ignore-cases.json, themselves checked
// against git.

export const GITIGNORE = ".gitignore";
export const DOCSWATCHERIGNORE = ".docswatcherignore";

/** One pattern line, relative to the directory of the file it came from. */
interface Rule { regex: RegExp; negate: boolean; dirOnly: boolean; nameOnly: boolean }

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

function parse(out: Rule[], text: string): void {
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
    out.push({ regex: new RegExp("^" + globToIgnoreRegex(line) + "$"), negate, dirOnly, nameOnly: !anchored });
  }
}

const dirOf = (path: string) => { const s = path.lastIndexOf("/"); return s < 0 ? "" : path.slice(0, s); };
/**
 * @param ignoreFiles the ignore files in the tree, as path and text; any other file is disregarded
 * @param exclude patterns for this run, applied last and relative to the root
 */
export function compileIgnore(ignoreFiles: Array<{ path: string; text: string }>, exclude: string[] = []): Ignore {
  // The last matching rule decides, trying the root .gitignore first, then the .gitignore of each
  // directory above the path from the top down, then .docswatcherignore, then this run's patterns.
  // Rules are kept by the directory they are scoped to, so a path only meets the rules that can
  // apply to it: a monorepo with hundreds of .gitignore files would otherwise try every rule of
  // every one of them against every directory of every path.
  const root: Rule[] = [];
  const nested = new Map<string, Rule[]>(); // directory -> the rules of the .gitignore in it
  for (const f of ignoreFiles) {
    if (f.path === GITIGNORE) parse(root, f.text);
    else if (f.path.endsWith("/" + GITIGNORE)) {
      const rules = nested.get(dirOf(f.path)) ?? [];
      parse(rules, f.text);
      if (rules.length) nested.set(dirOf(f.path), rules);
    }
  }
  const last: Rule[] = []; // .docswatcherignore, then this run's patterns
  const own = ignoreFiles.find((f) => f.path === DOCSWATCHERIGNORE);
  if (own) parse(last, own.text);
  if (exclude.length) parse(last, exclude.join("\n"));
  if (!root.length && !nested.size && !last.length) return NONE;

  // Tries rules from one ignore file against rel, the target relative to that file.
  const apply = (rules: Rule[], rel: string, isDir: boolean, excluded: boolean): boolean => {
    if (!rules.length) return excluded;
    const name = rel.slice(rel.lastIndexOf("/") + 1);
    for (const r of rules) {
      if (r.dirOnly && !isDir) continue;
      if (r.regex.test(r.nameOnly ? name : rel)) excluded = !r.negate;
    }
    return excluded;
  };

  const decide = (target: string, isDir: boolean): boolean => {
    let excluded = apply(root, target, isDir, false);
    if (nested.size) {
      for (let slash = target.indexOf("/"); slash >= 0; slash = target.indexOf("/", slash + 1)) {
        const rules = nested.get(target.slice(0, slash));
        if (rules) excluded = apply(rules, target.slice(slash + 1), isDir, excluded);
      }
    }
    return apply(last, target, isDir, excluded);
  };

  // Directory -> whether it or a directory above it is excluded. Files share directories.
  const dirs = new Map<string, boolean>();
  const dirExcluded = (dir: string): boolean => {
    const known = dirs.get(dir);
    if (known !== undefined) return known;
    const slash = dir.lastIndexOf("/");
    const excluded = (slash >= 0 && dirExcluded(dir.slice(0, slash))) || decide(dir, true);
    dirs.set(dir, excluded);
    return excluded;
  };

  return {
    // As in git, nothing inside an excluded directory comes back.
    ignored(path: string): boolean {
      const slash = path.lastIndexOf("/");
      if (slash >= 0 && dirExcluded(path.slice(0, slash))) return true;
      return decide(path, false);
    },
  };
}
