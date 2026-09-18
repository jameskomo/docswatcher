const SKIP_DIRS = new Set(["node_modules", "target", "dist", "build", ".git", "vendor", ".venv"]);
const MAX_BYTES = 1024 * 1024;

export function isSkippedDir(path: string): boolean {
  const segs = path.split("/");
  for (let i = 0; i < segs.length - 1; i++) if (SKIP_DIRS.has(segs[i])) return true;
  return false;
}

export function isTooLarge(text: string): boolean {
  // UTF-8 byte length without allocating: count code units and multi-byte overhead.
  let bytes = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80) bytes += 1; else if (c < 0x800) bytes += 2;
    else if (c >= 0xd800 && c <= 0xdbff) { bytes += 4; i++; } else bytes += 3;
    if (bytes > MAX_BYTES) return true;
  }
  return false;
}

const DOC_EXT = /\.(md|rst|txt|adoc)$/;
const TEST_SEGS = new Set(["test", "tests", "__tests__", "spec", "specs", "fixtures"]);

export function isDocOrTestPath(path: string): boolean {
  if (DOC_EXT.test(path)) return true;
  const segs = path.split("/");
  const file = segs[segs.length - 1];
  for (let i = 0; i < segs.length - 1; i++) if (TEST_SEGS.has(segs[i])) return true;
  return file.includes(".test.") || file.includes(".spec.") || file.includes("_test.") || file.endsWith("Test.java");
}

export function languageOf(path: string): "java" | "python" | "typescript" | "tsx" | "javascript" | "go" | null {
  const m = /\.([a-z]+)$/i.exec(path);
  if (!m) return null;
  switch (m[1].toLowerCase()) {
    case "java": return "java";
    case "py": return "python";
    case "ts": return "typescript";
    case "tsx": return "tsx";
    case "js": case "jsx": case "mjs": case "cjs": return "javascript";
    case "go": return "go";
    default: return null;
  }
}

export function lineStarts(text: string): number[] {
  const starts = [0];
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) starts.push(i + 1);
  return starts;
}

/** 1-based line and column for a character offset, given precomputed line starts. */
export function position(starts: number[], offset: number): { line: number; column: number } {
  let lo = 0, hi = starts.length - 1;
  while (lo < hi) { const mid = (lo + hi + 1) >> 1; if (starts[mid] <= offset) lo = mid; else hi = mid - 1; }
  return { line: lo + 1, column: offset - starts[lo] + 1 };
}

export function lineText(text: string, starts: number[], line: number): string {
  const s = starts[line - 1];
  const e = line < starts.length ? starts[line] - 1 : text.length;
  let t = text.slice(s, e);
  if (t.endsWith("\r")) t = t.slice(0, -1);
  return t;
}

export function snippetOf(text: string, starts: number[], line: number): string {
  const t = lineText(text, starts, line).trim();
  return t.length > 200 ? t.slice(0, 200) : t;
}
