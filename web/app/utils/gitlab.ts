import type { InputFile } from "~~/engine/types";
import { isBinary, pathIsSkipped } from "./tar";
import { applyIgnoreFiles, MAX_FILES, PRIORITY, type FetchProgress, type FetchResult } from "./fetchRepo";

/**
 * A GitLab project: on gitlab.com, or on a self-managed instance named by its full URL. The path
 * is every group and subgroup, then the project, because GitLab nests groups where GitHub has one
 * owner.
 */
export interface GitLabTarget { origin: string; path: string; ref: string | null; }

export const GITLAB_COM = "https://gitlab.com";

/** A host name with a dot, or localhost, optionally with a port. What a GitLab origin looks like. */
export const GITLAB_HOST = /^(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9-]+|localhost)(?::\d{1,5})?$/i;
/** One group or project path segment, as GitLab allows them: letters, digits, _, - and . */
const SEGMENT = /^[A-Za-z0-9_.][A-Za-z0-9_.-]*$/;

/**
 * Reads https://gitlab.com/group/sub/project, the same without a scheme, and any of them followed
 * by /-/tree/<ref>. Any other host with a dot in its name is taken to be a self-managed GitLab;
 * github.com never is, so a GitHub URL is never read as GitLab. Returns null for anything else.
 */
export function parseGitLabUrl(input: string): GitLabTarget | null {
  let s = input.trim();
  const scheme = /^(https?):\/\//i.exec(s);
  const protocol = scheme?.[1]?.toLowerCase() ?? "https";
  if (scheme) s = s.slice(scheme[0].length);
  s = s.replace(/[?#].*$/, "");
  const cut = s.indexOf("/-/");
  const projectPart = (cut >= 0 ? s.slice(0, cut) : s).replace(/\/+$/, "");
  const rest = cut >= 0 ? s.slice(cut + 3) : "";

  const parts = projectPart.split("/");
  const host = (parts.shift() ?? "").toLowerCase();
  if (!GITLAB_HOST.test(host) || /^(?:www\.)?github\.com$/.test(host)) return null;
  const last = parts.pop();
  if (last !== undefined) parts.push(last.replace(/\.git$/, ""));
  if (parts.length < 2 || !parts.every((p) => SEGMENT.test(p) && p !== "." && p !== "..")) return null;

  let ref: string | null = null;
  const tree = /^tree\/(.+?)\/?$/.exec(rest);
  if (tree?.[1]) {
    try { ref = decodeURIComponent(tree[1]); } catch { return null; }
  }
  return { origin: `${protocol}://${host}`, path: parts.join("/"), ref };
}

/** A page of the recursive tree is 100 entries. Fifty pages keep a scan inside gitlab.com's anonymous budget. */
const TREE_PAGES = 50;
const decoder = new TextDecoder("utf-8", { fatal: false });

/**
 * Reads a project through the GitLab REST API v4, which answers any origin with
 * Access-Control-Allow-Origin: *. One call resolves the project, one pins the ref to a commit,
 * the recursive tree is paged 100 entries at a time, then files are read one by one from the raw
 * endpoint at that commit. The same 300-file budget and ignore-file pre-filtering apply as on
 * GitHub.
 *
 * The archive endpoint also allows cross-origin reads, and would be one request. It is not used:
 * its size has no bound, and gitlab.com limits archive downloads far more tightly than API calls.
 *
 * Without a token gitlab.com allows 500 API requests a minute per address; a scan costs at most
 * 2 + 50 + 300 plus the ignore files. The rate limit headers are not exposed to scripts, so a 429
 * ends the scan with a message rather than a guessed wait.
 */
export async function fetchViaGitLab(t: GitLabTarget, token: string | undefined, progress: FetchProgress): Promise<FetchResult> {
  const host = new URL(t.origin).host;
  const api = `${t.origin}/api/v4`;
  const headers: Record<string, string> = token ? { "PRIVATE-TOKEN": token } : {};
  const policy = watchPolicy(t.origin);

  const call = async (url: string): Promise<Response> => {
    let res: Response;
    try { res = await fetch(url, { headers }); }
    catch (e) { throw await unreachable(t.origin, e, policy); }
    if (res.status === 429) {
      throw new Error(`GitLab's rate limit was reached at ${host}`
        + (t.origin === GITLAB_COM ? " (500 API requests a minute per address without a token)" : "")
        + `. Wait a minute and scan again${token ? "" : ", or paste a personal access token"}.`);
    }
    if (res.status === 401) throw new Error(`${host} did not accept the token. It needs the read_api scope.`);
    return res;
  };
  const json = async (res: Response, what: string) => {
    try { return await res.json(); }
    catch { throw new Error(`${host} did not answer ${what} like a GitLab API. Is it a GitLab instance?`); }
  };

  try {
    progress("Resolving project");
    const p = await call(`${api}/projects/${encodeURIComponent(t.path)}`);
    if (p.status === 404) {
      throw new Error(`No GitLab project at ${host}/${t.path}. Check the address. A private project needs a token with the read_api scope.`);
    }
    if (!p.ok) throw new Error(`GitLab at ${host} returned ${p.status} for ${t.path}`);
    const project = await json(p, "the project lookup");
    if (typeof project?.id !== "number") throw new Error(`${host} did not answer the project lookup like a GitLab API. Is it a GitLab instance?`);
    const ref: string | null = t.ref ?? project.default_branch ?? null;
    if (!ref) throw new Error(`${host}/${t.path} has no commits to scan.`);
    const base = `${api}/projects/${project.id}/repository`;

    progress("Resolving commit");
    const c = await call(`${base}/commits/${encodeURIComponent(ref)}`);
    if (c.status === 404) throw new Error(`${host}/${t.path} has no branch, tag or commit named "${ref}".`);
    if (!c.ok) throw new Error(`GitLab at ${host} returned ${c.status} resolving ${ref}`);
    const sha = String((await json(c, "the commit lookup")).id ?? "");
    if (!/^[0-9a-f]{7,64}$/.test(sha)) throw new Error(`${host} returned no commit for "${ref}".`);

    // Keyset pagination is GitLab's recommendation for large trees; an older instance ignores the
    // parameter and pages by number, which nextPage follows too.
    const entries: Array<{ path: string; type: string; mode: string }> = [];
    let next: string | null = `${base}/tree?ref=${sha}&recursive=true&per_page=100&pagination=keyset`;
    let pages = 0;
    let listingCut = false;
    while (next) {
      if (pages === TREE_PAGES) { listingCut = true; break; }
      progress(pages ? `Listing files, ${entries.length} so far` : "Listing files");
      const r = await call(next);
      if (!r.ok) throw new Error(`GitLab at ${host} returned ${r.status} listing ${t.path}`);
      const page = await json(r, "the file listing");
      if (!Array.isArray(page)) throw new Error(`${host} did not answer the file listing like a GitLab API.`);
      entries.push(...page);
      pages++;
      next = nextPage(r, next);
    }

    // Blobs only: mode 120000 is a symbolic link, and a submodule is listed as type "commit".
    const listed = entries
      .filter((e) => e?.type === "blob" && e.mode !== "120000" && typeof e.path === "string" && !pathIsSkipped(e.path))
      .map((e) => e.path);
    const rawUrl = (path: string) => `${base}/files/${encodeURIComponent(path)}/raw?ref=${sha}`;
    const { kept, ignoreFiles } = await applyIgnoreFiles(listed,
      async (path) => { const r = await call(rawUrl(path)); return r.ok ? r.text() : null; });
    const rank = (path: string) => { const i = PRIORITY.findIndex((re) => re.test(path)); return i < 0 ? PRIORITY.length : i; };
    const relevant = listed.filter((path) => kept.has(path) && rank(path) < PRIORITY.length)
      .sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
    const chosen = relevant.slice(0, MAX_FILES);

    const files: InputFile[] = [...ignoreFiles];
    let binaries = 0, done = 0;
    const queue = [...chosen];
    const worker = async () => {
      while (queue.length) {
        const path = queue.shift()!;
        const r = await call(rawUrl(path)); // a rate limit ends the scan: a partial one would read as clean
        if (r.ok) {
          const bytes = new Uint8Array(await r.arrayBuffer());
          if (bytes.byteLength > 1024 * 1024) { /* oversized, skip: the tree does not carry sizes */ }
          else if (isBinary(bytes)) binaries++;
          else files.push({ path, text: decoder.decode(bytes) });
        }
        done++;
        progress("Reading files", done, chosen.length);
      }
    };
    await Promise.all(Array.from({ length: 6 }, worker));
    files.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));

    const canonical = typeof project.path_with_namespace === "string" && parseGitLabUrl(`${host}/${project.path_with_namespace}`)
      ? project.path_with_namespace as string : t.path;
    const cut = canonical.lastIndexOf("/");
    return {
      files, binaries,
      skipped: listed.length - chosen.length,
      truncated: listingCut || relevant.length > chosen.length,
      repo: { host, owner: canonical.slice(0, cut), name: canonical.slice(cut + 1), ref: `refs/heads/${ref}`, sha },
    };
  } finally {
    policy.stop();
  }
}

/** The next page from the Link header, or X-Next-Page, kept on the URL the page was fetched from. */
function nextPage(res: Response, current: string): string | null {
  const url = new URL(current);
  const link = /<([^>]+)>\s*;\s*rel="next"/.exec(res.headers.get("link") ?? "");
  if (link?.[1]) {
    // An instance behind a proxy can advertise a different external URL. Only the query is taken,
    // so a token is never sent anywhere but the origin the visitor named.
    try { url.search = new URL(link[1], current).search; return url.toString(); } catch { return null; }
  }
  const page = res.headers.get("x-next-page");
  if (page && /^\d+$/.test(page)) { url.searchParams.set("page", page); return url.toString(); }
  return null;
}

/**
 * Notices when this page's Content-Security-Policy refuses a connection to the origin. The
 * browser reports that only as a failed fetch, the same as an unreachable host, and the two need
 * different advice.
 */
function watchPolicy(origin: string): { blocked: () => boolean; stop: () => void } {
  if (typeof document === "undefined") return { blocked: () => false, stop: () => {} };
  let hit = false;
  const onViolation = (e: SecurityPolicyViolationEvent) => {
    if (e.blockedURI === origin || e.blockedURI.startsWith(origin + "/")) hit = true;
  };
  document.addEventListener("securitypolicyviolation", onViolation);
  return { blocked: () => hit, stop: () => document.removeEventListener("securitypolicyviolation", onViolation) };
}

async function unreachable(origin: string, cause: unknown, policy: { blocked: () => boolean }): Promise<Error> {
  if (!(cause instanceof TypeError)) return cause instanceof Error ? cause : new Error(String(cause));
  // The violation event is queued as its own task and can land after the fetch rejects.
  await new Promise((r) => setTimeout(r, 50));
  if (policy.blocked()) {
    return new Error(`This page's Content-Security-Policy does not allow requests to ${origin}, so the project cannot be read from here.`
      + (origin === GITLAB_COM ? "" : ` A self-managed GitLab can be scanned from a copy of this site whose policy allows its host: run the site locally, or add ${origin} to connect-src where you host it.`)
      + " Sample repositories and local folders still work.");
  }
  return new Error(`Could not reach ${origin}. The instance may only be reachable from another network, or it may not answer cross-origin requests.`);
}
