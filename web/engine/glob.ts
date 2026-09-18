/** Minimal glob to RegExp: supports **, *, ?, and {a,b} alternation. Matches full relative paths. */
export function globToRegExp(glob: string): RegExp {
  let re = "";
  let i = 0;
  while (i < glob.length) {
    const c = glob[i];
    if (c === "*") {
      if (glob[i + 1] === "*") {
        // "**/" matches zero or more directories; bare "**" matches anything
        if (glob[i + 2] === "/") { re += "(?:.*/)?"; i += 3; } else { re += ".*"; i += 2; }
        continue;
      }
      re += "[^/]*"; i++; continue;
    }
    if (c === "?") { re += "[^/]"; i++; continue; }
    if (c === "{") {
      const end = glob.indexOf("}", i);
      if (end > i) {
        const alts = glob.slice(i + 1, end).split(",").map(escapeRe);
        re += "(?:" + alts.join("|") + ")"; i = end + 1; continue;
      }
    }
    re += escapeRe(c); i++;
  }
  return new RegExp("^" + re + "$");
}

function escapeRe(s: string): string { return s.replace(/[.+^$()|[\]\\]/g, "\\$&"); }

export function matchesAny(path: string, globs: string[] | undefined, cache: Map<string, RegExp>): boolean {
  if (!globs || globs.length === 0) return false;
  for (const g of globs) {
    let re = cache.get(g);
    if (!re) { re = globToRegExp(g); cache.set(g, re); }
    if (re.test(path)) return true;
  }
  return false;
}
