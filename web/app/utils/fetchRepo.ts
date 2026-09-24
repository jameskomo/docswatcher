import type { InputFile, RepoRef } from "~~/engine/types";
import { gunzip, isBinary, pathIsSkipped, untar } from "./tar";
import { compileIgnore, isIgnoreFile } from "~~/engine/ignore";

export interface RepoTarget { owner: string; name: string; ref: string | null; }

export function parseGitHubUrl(input: string): RepoTarget | null {
  const s = input.trim().replace(/\.git$/, "");
  let m = /^(?:https?:\/\/)?(?:www\.)?github\.com\/([\w.-]+)\/([\w.-]+)(?:\/tree\/([^\s]+))?\/?$/.exec(s);
  if (m) return { owner: m[1], name: m[2], ref: m[3] ? decodeURIComponent(m[3]) : null };
  m = /^([\w.-]+)\/([\w.-]+)$/.exec(s);
  if (m) return { owner: m[1], name: m[2], ref: null };
  return null;
}

export interface FetchProgress { (msg: string, done?: number, total?: number): void; }
export interface FetchResult { files: InputFile[]; repo: RepoRef; binaries: number; skipped: number; truncated: boolean; }

const MAX_FILES = 300;

/**
 * Reads the repository's ignore files first and drops what they exclude, so excluded fixtures
 * and generated data cannot fill the MAX_FILES budget and push real code out of the scan
 * (docs/18-excluding-paths.md). Returns the kept paths and the ignore files, which the scan
 * needs too.
 */
async function applyIgnoreFiles(paths: string[], read: (path: string) => Promise<string | null>): Promise<{ kept: Set<string>; ignoreFiles: InputFile[] }> {
  const ignoreFiles: InputFile[] = [];
  for (const path of paths.filter(isIgnoreFile)) {
    const text = await read(path).catch(() => null);
    if (text !== null) ignoreFiles.push({ path, text });
  }
  const ignore = compileIgnore(ignoreFiles);
  return { kept: new Set(paths.filter((p) => !ignore.ignored(p))), ignoreFiles };
}
const decoder = new TextDecoder("utf-8", { fatal: false });

export async function fetchViaRelay(relay: string, t: RepoTarget, progress: FetchProgress): Promise<FetchResult> {
  const url = `${relay.replace(/\/$/, "")}/tarball/${t.owner}/${t.name}${t.ref ? "/" + encodeURIComponent(t.ref) : ""}`;
  progress("Downloading repository archive");
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Relay returned ${res.status} for ${t.owner}/${t.name}`);
  const sha = res.headers.get("x-docswatcher-sha") ?? "unknown";
  const ref = res.headers.get("x-docswatcher-ref") ?? t.ref ?? "HEAD";
  const gz = new Uint8Array(await res.arrayBuffer());
  progress("Unpacking");
  const tar = await gunzip(gz);
  const { files, binaries, skipped } = untar(tar);
  return { files, binaries, skipped, truncated: false, repo: { host: "github", owner: t.owner, name: t.name, ref, sha } };
}

/**
 * The REST API version the browser pins, the same one the GitHub App uses. An unpinned request
 * gets GitHub's default, which is 2022-11-28 until that version stops being served on 2028-03-10.
 * 2026-03-10 changes nothing read here: default_branch, and the tree's sha, path, type and size.
 * GitHub's CORS policy allows the header, so an anonymous call now costs a preflight, cached a day.
 */
export const GITHUB_API_VERSION = "2026-03-10";

async function gh(url: string, token?: string) {
  const headers: Record<string, string> = { Accept: "application/vnd.github+json", "X-GitHub-Api-Version": GITHUB_API_VERSION };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, { headers });
  if (res.status === 403 || res.status === 429) {
    const reset = res.headers.get("x-ratelimit-reset");
    const when = reset ? new Date(Number(reset) * 1000).toLocaleTimeString() : "later";
    throw new Error(`GitHub API rate limit reached (60 requests per hour without a token). Try again after ${when}, or paste a personal token.`);
  }
  if (res.status === 404) throw new Error("Repository not found. Private repositories need a token with repo read access.");
  if (!res.ok) throw new Error(`GitHub API returned ${res.status}`);
  return res.json();
}

const PRIORITY = [/(^|\/)(package\.json|requirements[^/]*\.txt|pyproject\.toml|pom\.xml|go\.mod|Gemfile)$/, /\.(ya?ml|toml|env|properties|json)$/, /\.(java|kt|ts|tsx|js|jsx|mjs|cjs|py|go|rb|php|cs)$/];

/** Fallback without a relay: one tree call to the API, then raw file reads (not counted against the API limit). */
export async function fetchViaApi(t: RepoTarget, token: string | undefined, progress: FetchProgress): Promise<FetchResult> {
  progress("Resolving default branch");
  let ref = t.ref;
  if (!ref) { const info = await gh(`https://api.github.com/repos/${t.owner}/${t.name}`, token); ref = info.default_branch as string; }
  progress("Listing files");
  const tree = await gh(`https://api.github.com/repos/${t.owner}/${t.name}/git/trees/${encodeURIComponent(ref)}?recursive=1`, token);
  const sha: string = tree.sha;
  const listed: Array<{ path: string; size: number }> = (tree.tree as any[])
    .filter((e) => e.type === "blob" && !pathIsSkipped(e.path) && e.size <= 1024 * 1024)
    .map((e) => ({ path: e.path, size: e.size }));
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  const rawUrl = (path: string) => `https://raw.githubusercontent.com/${t.owner}/${t.name}/${sha}/${path.split("/").map(encodeURIComponent).join("/")}`;
  const { kept, ignoreFiles } = await applyIgnoreFiles(listed.map((e) => e.path),
    async (path) => { const r = await fetch(rawUrl(path), { headers }); return r.ok ? r.text() : null; });
  const all = listed.filter((e) => kept.has(e.path));
  const rank = (p: string) => { for (let i = 0; i < PRIORITY.length; i++) if (PRIORITY[i].test(p)) return i; return PRIORITY.length; };
  all.sort((a, b) => rank(a.path) - rank(b.path) || a.path.localeCompare(b.path));
  const chosen = all.filter((e) => rank(e.path) < PRIORITY.length).slice(0, MAX_FILES);
  const truncated = all.filter((e) => rank(e.path) < PRIORITY.length).length > chosen.length;
  const files: InputFile[] = [...ignoreFiles];
  let binaries = 0, done = 0;
  const queue = [...chosen];
  const worker = async () => {
    while (queue.length) {
      const e = queue.shift()!;
      const res = await fetch(rawUrl(e.path), { headers });
      if (res.ok) {
        const bytes = new Uint8Array(await res.arrayBuffer());
        if (isBinary(bytes)) binaries++; else files.push({ path: e.path, text: decoder.decode(bytes) });
      }
      done++;
      progress("Reading files", done, chosen.length);
    }
  };
  await Promise.all(Array.from({ length: 6 }, worker));
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { files, binaries, skipped: listed.length - chosen.length, truncated, repo: { host: "github", owner: t.owner, name: t.name, ref: `refs/heads/${ref}`, sha } };
}

/**
 * Reads a public repository through jsDelivr. Preferred over the GitHub API because
 * jsDelivr imposes no per-hour request ceiling and both of its hosts answer with
 * Access-Control-Allow-Origin, so it also works from restrictive embedding hosts.
 * jsDelivr caches branch refs, so a scan can lag the very latest commit.
 */
export async function fetchViaJsDelivr(t: RepoTarget, progress: FetchProgress): Promise<FetchResult> {
  const ref = t.ref ?? "HEAD";
  progress("Listing files");
  const listUrl = `https://data.jsdelivr.com/v1/packages/gh/${t.owner}/${t.name}@${encodeURIComponent(ref)}?structure=flat`;
  const res = await fetch(listUrl);
  if (res.status === 404) throw new Error(`jsDelivr could not resolve ${t.owner}/${t.name}@${ref}. Check the repository name, or try without a branch.`);
  if (!res.ok) throw new Error(`jsDelivr listing returned ${res.status}`);
  const data = await res.json();

  const listed: string[] = (data.files ?? [])
    .map((f: any) => String(f.name ?? "").replace(/^\//, ""))
    .filter((path: string) => path && !pathIsSkipped(path));
  const base = `https://cdn.jsdelivr.net/gh/${t.owner}/${t.name}@${encodeURIComponent(ref)}`;
  const { kept, ignoreFiles } = await applyIgnoreFiles(listed,
    async (path) => { const r = await fetch(`${base}/${path.split("/").map(encodeURIComponent).join("/")}`); return r.ok ? r.text() : null; });
  const all = listed.filter((p) => kept.has(p));
  const rank = (p: string) => { for (let i = 0; i < PRIORITY.length; i++) if (PRIORITY[i].test(p)) return i; return PRIORITY.length; };
  const relevant = all.filter((p) => rank(p) < PRIORITY.length).sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
  const chosen = relevant.slice(0, MAX_FILES);
  const truncated = relevant.length > chosen.length;

  const files: InputFile[] = [...ignoreFiles];
  let binaries = 0, done = 0;
  const queue = [...chosen];
  const worker = async () => {
    while (queue.length) {
      const path = queue.shift()!;
      try {
        const r = await fetch(`${base}/${path.split("/").map(encodeURIComponent).join("/")}`);
        if (r.ok) {
          const bytes = new Uint8Array(await r.arrayBuffer());
          if (bytes.byteLength > 1024 * 1024) { /* oversized, skip */ }
          else if (isBinary(bytes)) binaries++;
          else files.push({ path, text: decoder.decode(bytes) });
        }
      } catch { /* one unreadable file must not fail the scan */ }
      done++;
      progress("Reading files", done, chosen.length);
    }
  };
  await Promise.all(Array.from({ length: 6 }, worker));
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return {
    files, binaries, truncated,
    skipped: listed.length - chosen.length,
    repo: { host: "github", owner: t.owner, name: t.name, ref, sha: String(data.version ?? ref) },
  };
}

/** Reads a dropped or picked directory. Skips vendored directories before reading to keep it fast. */
export async function readFolder(list: FileList, progress: FetchProgress): Promise<{ files: InputFile[]; binaries: number; skipped: number; root: string }> {
  const entries = Array.from(list);
  const root = entries[0]?.webkitRelativePath.split("/")[0] ?? "folder";
  const relOf = (f: File) => f.webkitRelativePath.split("/").slice(1).join("/");
  const readable = entries.filter((f) => { const rel = relOf(f); return rel && !pathIsSkipped(rel) && f.size <= 1024 * 1024; });
  // A local folder is where gitignored build output lives: read the ignore files first and never
  // open what they exclude (docs/18-excluding-paths.md).
  const byPath = new Map(readable.map((f) => [relOf(f), f]));
  const { kept } = await applyIgnoreFiles([...byPath.keys()], async (path) => byPath.get(path)!.text());
  const candidates = readable.filter((f) => kept.has(relOf(f)));
  const files: InputFile[] = [];
  let binaries = 0, done = 0;
  for (const f of candidates) {
    const rel = relOf(f);
    const bytes = new Uint8Array(await f.arrayBuffer());
    if (isBinary(bytes)) binaries++; else files.push({ path: rel, text: decoder.decode(bytes) });
    done++;
    if (done % 25 === 0 || done === candidates.length) progress("Reading files", done, candidates.length);
  }
  files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  return { files, binaries, skipped: entries.length - candidates.length, root };
}
