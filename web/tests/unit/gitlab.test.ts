// GitLab in the browser scanner: reading the address, the share link round trip, and the API route
// against a mocked GitLab.
import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchViaGitLab, parseGitLabUrl } from "../../app/utils/gitlab";
import { evidenceUrl, parseScanInput, parseShareParam, shareParam, targetLabel } from "../../app/utils/scanTarget";
import type { Evidence, RepoRef } from "../../engine/types";

afterEach(() => vi.unstubAllGlobals());

describe("parseGitLabUrl", () => {
  it.each([
    ["https://gitlab.com/group/project", { origin: "https://gitlab.com", path: "group/project", ref: null }],
    ["gitlab.com/group/project", { origin: "https://gitlab.com", path: "group/project", ref: null }],
    ["  https://gitlab.com/group/sub/deeper/project.git/ ", { origin: "https://gitlab.com", path: "group/sub/deeper/project", ref: null }],
    ["https://gitlab.com/group/project/-/tree/feature/x", { origin: "https://gitlab.com", path: "group/project", ref: "feature/x" }],
    ["https://gitlab.com/group/project/-/tree/v1.2%2Bbuild/", { origin: "https://gitlab.com", path: "group/project", ref: "v1.2+build" }],
    ["https://gitlab.com/group/project/-/blob/main/README.md", { origin: "https://gitlab.com", path: "group/project", ref: null }],
    ["https://gitlab.com/group/project?tab=readme#top", { origin: "https://gitlab.com", path: "group/project", ref: null }],
    ["https://GitLab.Example.com:8443/Team/sub/my.project", { origin: "https://gitlab.example.com:8443", path: "Team/sub/my.project", ref: null }],
    ["http://localhost:8929/group/project", { origin: "http://localhost:8929", path: "group/project", ref: null }],
  ])("reads %s", (input, want) => {
    expect(parseGitLabUrl(input)).toEqual(want);
  });

  it.each([
    "https://github.com/owner/repo",
    "github.com/owner/repo",
    "https://gitlab.com/project-only",
    "https://gitlab.com/",
    "owner/repo",
    "https://gitlab.com/group/../project",
    "https://gitlab.com/group/-bad/project",
    "ftp://gitlab.com/group/project",
    "javascript:alert(1)//gitlab.com/a/b",
    "https://gitlab.com/group/project/-/tree/%E0%A4%A",
  ])("rejects %s", (input) => {
    expect(parseGitLabUrl(input)).toBeNull();
  });
});

describe("parseScanInput", () => {
  it("keeps every GitHub form it always accepted", () => {
    expect(parseScanInput("https://github.com/openai/openai-quickstart-python")).toEqual({ host: "github", repo: { owner: "openai", name: "openai-quickstart-python", ref: null } });
    expect(parseScanInput("openai/openai-quickstart-python")).toEqual({ host: "github", repo: { owner: "openai", name: "openai-quickstart-python", ref: null } });
    expect(parseScanInput("github.com/vercel/next.js/tree/canary")).toEqual({ host: "github", repo: { owner: "vercel", name: "next.js", ref: "canary" } });
  });

  it("reads a host-led path as GitLab, not as a GitHub owner with a dot", () => {
    expect(parseScanInput("gitlab.com/group/project")).toEqual({ host: "gitlab", project: { origin: "https://gitlab.com", path: "group/project", ref: null } });
    expect(parseScanInput("gitlab.com/group")).toBeNull();
  });

  it("labels each by what a person would type", () => {
    expect(targetLabel(parseScanInput("openai/x")!)).toBe("openai/x");
    expect(targetLabel(parseScanInput("https://gitlab.example.com/a/b/c")!)).toBe("gitlab.example.com/a/b/c");
  });
});

describe("share links", () => {
  const gh: RepoRef = { host: "github", owner: "openai", name: "openai-quickstart-python", ref: "HEAD", sha: "ec8890d" };
  const gl: RepoRef = { host: "gitlab.com", owner: "group/sub", name: "project", ref: "refs/heads/main", sha: "a".repeat(40) };
  const self: RepoRef = { host: "gitlab.example.com:8443", owner: "team", name: "api", ref: "refs/heads/main", sha: "b".repeat(40) };

  it("keeps GitHub links exactly as they were", () => {
    expect(shareParam(gh)).toBe("openai/openai-quickstart-python");
    expect(parseShareParam("openai/openai-quickstart-python")).toEqual({ host: "github", repo: { owner: "openai", name: "openai-quickstart-python", ref: null } });
  });

  it("round-trips gitlab.com, nested groups and a self-managed host", () => {
    expect(shareParam(gl)).toBe("gitlab.com/group/sub/project");
    expect(parseShareParam("gitlab.com/group/sub/project")).toEqual({ host: "gitlab", project: { origin: "https://gitlab.com", path: "group/sub/project", ref: null } });
    expect(shareParam(self)).toBe("gitlab.example.com:8443/team/api");
    expect(parseShareParam("gitlab.example.com:8443/team/api")).toEqual({ host: "gitlab", project: { origin: "https://gitlab.example.com:8443", path: "team/api", ref: null } });
  });

  it("offers no link for a sample fixture or a folder", () => {
    expect(shareParam({ host: "fixture", owner: "docswatcher", name: "x", ref: "fixture", sha: "0" })).toBe("");
    expect(shareParam({ host: "local", owner: "local", name: "x", ref: "working-tree", sha: "0" })).toBe("");
  });

  it.each([
    "https://gitlab.com/group/project",
    "gitlab.com/group/project/-/tree/main",
    "gitlab.com/group/project?x=1",
    "gitlab.com/group/../project",
    "javascript:alert(1)",
    "evil.com@gitlab.com/a/b",
    "gitlab.com/group",
  ])("refuses the crafted value %s", (value) => {
    expect(parseShareParam(value)).toBeNull();
  });

  it("links evidence to the scanned commit on either host", () => {
    const e = { path: "src/app one.py", line: 7 } as Evidence;
    expect(evidenceUrl(gh, e)).toBe("https://github.com/openai/openai-quickstart-python/blob/ec8890d/src/app%20one.py#L7");
    expect(evidenceUrl(gl, e)).toBe(`https://gitlab.com/group/sub/project/-/blob/${"a".repeat(40)}/src/app%20one.py#L7`);
    expect(evidenceUrl({ host: "fixture", owner: "d", name: "x", ref: "fixture", sha: "0" }, e)).toBeNull();
  });
});

/** A small GitLab: a project in a nested group, a two-page tree, and raw files at one commit. */
function stubGitLab(opts: { tree?: any[]; perPage?: number; paging?: "link" | "x-next-page" | "foreign-link"; files?: Record<string, string>; status?: Record<string, number> } = {}) {
  const sha = "0123456789abcdef0123456789abcdef01234567";
  const files: Record<string, string> = opts.files ?? {
    ".gitignore": "fixtures/\n",
    "package.json": '{"dependencies":{"openai":"^4.0.0"}}',
    "src/app.ts": 'client.chat.completions.create({ model: "gpt-3.5-turbo-0301" });',
    "fixtures/recorded.json": '{"model":"gpt-4-0314"}',
    "README.md": "# hi",
  };
  const tree = opts.tree ?? [
    ...Object.keys(files).map((path) => ({ id: "x", name: path.split("/").pop(), type: "blob", path, mode: "100644" })),
    { id: "x", name: "src", type: "tree", path: "src", mode: "040000" },
    { id: "x", name: "link.ts", type: "blob", path: "link.ts", mode: "120000" },
    { id: "x", name: "vendor-sub", type: "commit", path: "vendor-sub", mode: "160000" },
  ];
  const perPage = opts.perPage ?? 4;
  const calls: Array<{ url: string; headers: Record<string, string> }> = [];
  const api = "https://gitlab.com/api/v4";
  vi.stubGlobal("fetch", async (url: string, init?: { headers?: Record<string, string> }) => {
    calls.push({ url, headers: init?.headers ?? {} });
    const status = Object.entries(opts.status ?? {}).find(([prefix]) => url.startsWith(prefix))?.[1];
    if (status) return new Response("{}", { status });
    if (url === `${api}/projects/group%2Fsub%2Fproject`) {
      return Response.json({ id: 42, default_branch: "main", path_with_namespace: "Group/Sub/Project" });
    }
    if (url === `${api}/projects/42/repository/commits/main`) return Response.json({ id: sha });
    if (url.startsWith(`${api}/projects/42/repository/tree?`)) {
      const u = new URL(url);
      expect(u.searchParams.get("ref")).toBe(sha);
      expect(u.searchParams.get("recursive")).toBe("true");
      const page = Number(u.searchParams.get("page") ?? "1");
      const slice = tree.slice((page - 1) * perPage, page * perPage);
      const more = page * perPage < tree.length;
      const headers: Record<string, string> = {};
      if (more && opts.paging === "x-next-page") headers["x-next-page"] = String(page + 1);
      if (more && (opts.paging ?? "link") === "link") {
        const next = new URL(url); next.searchParams.set("page", String(page + 1));
        headers.link = `<${next}>; rel="next", <${url}>; rel="first"`;
      }
      if (more && opts.paging === "foreign-link") {
        headers.link = `<http://internal.example:8080/api/v4/projects/42/repository/tree?ref=${sha}&recursive=true&per_page=100&page=${page + 1}>; rel="next"`;
      }
      return Response.json(slice, { headers });
    }
    const raw = new RegExp(`^${api}/projects/42/repository/files/([^/]+)/raw\\?ref=${sha}$`).exec(url);
    if (raw) {
      const path = decodeURIComponent(raw[1]);
      return path in files ? new Response(files[path]) : new Response("", { status: 404 });
    }
    return new Response("unexpected " + url, { status: 500 });
  });
  return { calls, sha };
}

describe("fetchViaGitLab", () => {
  const target = { origin: "https://gitlab.com", path: "group/sub/project", ref: null };

  it("resolves the project and commit, pages the tree, honours .gitignore and reads files at the commit", async () => {
    const { calls, sha } = stubGitLab();
    const result = await fetchViaGitLab(target, undefined, () => {});
    expect(calls[0].url).toBe("https://gitlab.com/api/v4/projects/group%2Fsub%2Fproject");
    expect(calls[1].url).toBe("https://gitlab.com/api/v4/projects/42/repository/commits/main");
    expect(calls.filter((c) => c.url.includes("/repository/tree?")).length).toBe(2);
    expect(result.files.map((f) => f.path)).toEqual([".gitignore", "package.json", "src/app.ts"]);
    // Excluded by .gitignore before the budget was spent: never read.
    expect(calls.some((c) => c.url.includes("fixtures%2Frecorded.json"))).toBe(false);
    // Symbolic links and submodules are not files.
    expect(calls.some((c) => c.url.includes("link.ts") || c.url.includes("vendor-sub"))).toBe(false);
    expect(result.repo).toEqual({ host: "gitlab.com", owner: "Group/Sub", name: "Project", ref: "refs/heads/main", sha });
    expect(result.truncated).toBe(false);
    for (const c of calls) expect(c.headers["PRIVATE-TOKEN"]).toBeUndefined();
  });

  it("sends a token as PRIVATE-TOKEN, only to the instance", async () => {
    const { calls } = stubGitLab();
    await fetchViaGitLab(target, "glpat-secret", () => {});
    for (const c of calls) {
      expect(c.url.startsWith("https://gitlab.com/api/v4/")).toBe(true);
      expect(c.headers["PRIVATE-TOKEN"]).toBe("glpat-secret");
    }
  });

  it("follows X-Next-Page when there is no Link header", async () => {
    const { calls } = stubGitLab({ paging: "x-next-page" });
    const result = await fetchViaGitLab(target, undefined, () => {});
    expect(calls.filter((c) => c.url.includes("/repository/tree?")).length).toBe(2);
    expect(result.files.map((f) => f.path)).toContain("src/app.ts");
  });

  it("keeps paging on the origin it was given when the Link header names another", async () => {
    const { calls } = stubGitLab({ paging: "foreign-link" });
    await fetchViaGitLab(target, "glpat-secret", () => {});
    expect(calls.every((c) => c.url.startsWith("https://gitlab.com/"))).toBe(true);
    expect(calls.filter((c) => c.url.includes("/repository/tree?")).length).toBe(2);
  });

  it("reads at most 300 files and says so", async () => {
    const files: Record<string, string> = {};
    for (let i = 0; i < 320; i++) files[`src/m${String(i).padStart(3, "0")}.ts`] = "export {}";
    stubGitLab({ files, perPage: 100 });
    const result = await fetchViaGitLab(target, undefined, () => {});
    expect(result.files.length).toBe(300);
    expect(result.truncated).toBe(true);
  });

  it("stops listing after 50 pages and marks the scan truncated", async () => {
    const files: Record<string, string> = {};
    for (let i = 0; i < 60; i++) files[`src/m${String(i).padStart(2, "0")}.ts`] = "export {}";
    const { calls } = stubGitLab({ files, perPage: 1 });
    const result = await fetchViaGitLab(target, undefined, () => {});
    expect(calls.filter((c) => c.url.includes("/repository/tree?")).length).toBe(50);
    expect(result.truncated).toBe(true);
  });

  it("names a missing project, a rate limit and a refused token", async () => {
    stubGitLab({ status: { "https://gitlab.com/api/v4/projects/group%2Fsub%2Fproject": 404 } });
    await expect(fetchViaGitLab(target, undefined, () => {})).rejects.toThrow(/No GitLab project at gitlab\.com\/group\/sub\/project.*read_api/);
    stubGitLab({ status: { "https://gitlab.com/api/v4/projects/42/repository/tree": 429 } });
    await expect(fetchViaGitLab(target, undefined, () => {})).rejects.toThrow(/rate limit.*500 API requests a minute.*token/);
    stubGitLab({ status: { "https://gitlab.com/api/v4/projects/": 401 } });
    await expect(fetchViaGitLab(target, "bad", () => {})).rejects.toThrow(/did not accept the token/);
  });

  it("ends the scan on a rate limit while reading files, rather than returning a partial one", async () => {
    stubGitLab({ status: { "https://gitlab.com/api/v4/projects/42/repository/files/src%2Fapp.ts": 429 } });
    await expect(fetchViaGitLab(target, undefined, () => {})).rejects.toThrow(/rate limit/);
  });

  it("says when a host is not a GitLab API", async () => {
    vi.stubGlobal("fetch", async () => new Response("<html>hello</html>", { headers: { "content-type": "text/html" } }));
    await expect(fetchViaGitLab({ origin: "https://example.com", path: "a/b", ref: null }, undefined, () => {})).rejects.toThrow(/Is it a GitLab instance/);
  });

  it("says a host could not be reached instead of passing on a bare TypeError", async () => {
    vi.stubGlobal("fetch", async () => { throw new TypeError("Failed to fetch"); });
    await expect(fetchViaGitLab({ origin: "https://gitlab.internal.example", path: "a/b", ref: null }, undefined, () => {})).rejects.toThrow(/Could not reach https:\/\/gitlab\.internal\.example/);
  });

  it("uses the ref from the address instead of the default branch", async () => {
    const { calls } = stubGitLab({ status: {} });
    await fetchViaGitLab({ ...target, ref: "main" }, undefined, () => {});
    expect(calls[1].url).toBe("https://gitlab.com/api/v4/projects/42/repository/commits/main");
  });
});
