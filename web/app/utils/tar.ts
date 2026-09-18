import type { InputFile } from "~~/engine/types";

const SKIP = new Set(["node_modules", "target", "dist", "build", ".git", "vendor", ".venv"]);
const decoder = new TextDecoder("utf-8", { fatal: false });

export function isBinary(bytes: Uint8Array): boolean {
  const n = Math.min(bytes.length, 8000);
  for (let i = 0; i < n; i++) if (bytes[i] === 0) return true;
  return false;
}

export function pathIsSkipped(path: string): boolean {
  return path.split("/").slice(0, -1).some((s) => SKIP.has(s));
}

/** Parses a (gunzipped) ustar/pax tarball. Strips the first path segment, as GitHub tarballs nest everything under one folder. */
export function untar(bytes: Uint8Array, opts: { stripFirstSegment?: boolean; maxFileBytes?: number } = {}): { files: InputFile[]; binaries: number; skipped: number } {
  const strip = opts.stripFirstSegment ?? true;
  const max = opts.maxFileBytes ?? 1024 * 1024;
  const files: InputFile[] = [];
  let binaries = 0, skipped = 0;
  let off = 0;
  let longName: string | null = null;
  let paxPath: string | null = null;
  const str = (start: number, len: number) => {
    let end = start;
    while (end < start + len && bytes[end] !== 0) end++;
    return decoder.decode(bytes.subarray(start, end));
  };
  while (off + 512 <= bytes.length) {
    if (bytes[off] === 0) break; // end-of-archive zero blocks
    let name = str(off, 100);
    const size = parseInt(str(off + 124, 12).trim() || "0", 8);
    const type = String.fromCharCode(bytes[off + 156] || 48);
    const prefix = str(off + 345, 155);
    if (prefix) name = prefix + "/" + name;
    const dataStart = off + 512;
    const dataEnd = dataStart + size;
    const next = dataStart + Math.ceil(size / 512) * 512;
    if (type === "L") { longName = decoder.decode(bytes.subarray(dataStart, dataEnd)).replace(/\0+$/, ""); off = next; continue; }
    if (type === "x") {
      const rec = decoder.decode(bytes.subarray(dataStart, dataEnd));
      const m = /(?:^|\n)\d+ path=([^\n]*)/.exec(rec);
      paxPath = m ? m[1] : null; off = next; continue;
    }
    if (type === "g") { off = next; continue; }
    if (longName) { name = longName; longName = null; }
    if (paxPath) { name = paxPath; paxPath = null; }
    if (type === "0" || type === "\0" || type === "7") {
      let path = name.replace(/^\.\//, "");
      if (strip) path = path.split("/").slice(1).join("/");
      if (path && !pathIsSkipped(path)) {
        if (size > max) skipped++;
        else {
          const data = bytes.subarray(dataStart, dataEnd);
          if (isBinary(data)) binaries++; else files.push({ path, text: decoder.decode(data) });
        }
      }
    }
    off = next;
  }
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { files, binaries, skipped };
}

export async function gunzip(bytes: Uint8Array): Promise<Uint8Array> {
  const pako = await import("pako");
  return pako.ungzip(bytes);
}
