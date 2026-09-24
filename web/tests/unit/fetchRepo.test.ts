// The browser's GitHub API fallback pins the REST API version, like the GitHub App does.
import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchViaApi, GITHUB_API_VERSION } from "../../app/utils/fetchRepo";

afterEach(() => vi.unstubAllGlobals());

function stubGitHub() {
  const calls: Array<{ url: string; headers: Record<string, string> }> = [];
  vi.stubGlobal("fetch", async (url: string, init?: { headers?: Record<string, string> }) => {
    calls.push({ url, headers: init?.headers ?? {} });
    if (url === "https://api.github.com/repos/o/r") return Response.json({ default_branch: "main" });
    if (url.startsWith("https://api.github.com/repos/o/r/git/trees/main")) {
      return Response.json({ sha: "abc123", tree: [{ path: "package.json", type: "blob", size: 5 }] });
    }
    return new Response("hello");
  });
  return calls;
}

describe("fetchViaApi", () => {
  it("pins 2026-03-10", () => {
    expect(GITHUB_API_VERSION).toBe("2026-03-10");
  });

  it("sends the version on every api.github.com call, and not to raw.githubusercontent.com", async () => {
    const calls = stubGitHub();
    const result = await fetchViaApi({ owner: "o", name: "r", ref: null }, undefined, () => {});
    const api = calls.filter((c) => c.url.startsWith("https://api.github.com/"));
    expect(api.map((c) => c.url)).toEqual([
      "https://api.github.com/repos/o/r",
      "https://api.github.com/repos/o/r/git/trees/main?recursive=1",
    ]);
    for (const c of api) expect(c.headers["X-GitHub-Api-Version"]).toBe("2026-03-10");
    // raw.githubusercontent.com is not the REST API, and a custom header there only adds a preflight.
    for (const c of calls.filter((c) => !c.url.startsWith("https://api.github.com/"))) {
      expect(c.headers["X-GitHub-Api-Version"]).toBeUndefined();
    }
    expect(calls.some((c) => c.url.startsWith("https://raw.githubusercontent.com/"))).toBe(true);
    expect(result.repo).toMatchObject({ ref: "refs/heads/main", sha: "abc123" });
  });
});
