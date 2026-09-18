// DocWatcher tarball relay.
//
// GET /tarball/:owner/:repo[/:ref]  ->  streams the repository tarball from GitHub
// with permissive CORS headers so the browser scanner can read it. Holds no state.
// The browser may forward its own GitHub token in the Authorization header for
// private repositories; it is passed through to GitHub and never stored.
//
// See docs/adr/0003-browser-scanning-and-repo-fetch.md for why this exists.

const MAX_BYTES = 60 * 1024 * 1024;
const OWNER_RE = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/;
const REPO_RE = /^[A-Za-z0-9._-]{1,100}$/;
const REF_RE = /^[A-Za-z0-9._/-]{1,200}$/;

export default {
  async fetch(request, env, ctx) {
    return handle(request, env, ctx, globalThis.fetch, caches?.default);
  },
};

export async function handle(request, env, ctx, upstreamFetch, cache) {
  const origin = request.headers.get("Origin") ?? "*";
  const cors = corsHeaders(origin, env);

  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: cors });
  }
  if (request.method !== "GET") {
    return json({ error: "method not allowed" }, 405, cors);
  }

  const url = new URL(request.url);
  const parts = url.pathname.split("/").filter(Boolean);

  if (parts.length === 0 || parts[0] === "health") {
    return json({ ok: true, service: "docwatcher-relay" }, 200, cors);
  }
  if (parts[0] !== "tarball" || parts.length < 3) {
    return json({ error: "use /tarball/:owner/:repo[/:ref]" }, 404, cors);
  }

  const [, owner, repo, ...refParts] = parts;
  const ref = refParts.join("/") || "";
  if (!OWNER_RE.test(owner) || !REPO_RE.test(repo) || (ref && !REF_RE.test(ref))) {
    return json({ error: "invalid owner, repo, or ref" }, 400, cors);
  }

  const upstream = `https://api.github.com/repos/${owner}/${repo}/tarball/${ref}`;
  const headers = new Headers({
    "User-Agent": "docwatcher-relay/0.1 (+https://github.com)",
    Accept: "application/vnd.github+json",
  });
  const clientAuth = request.headers.get("Authorization");
  if (clientAuth) headers.set("Authorization", clientAuth);
  else if (env?.GITHUB_TOKEN) headers.set("Authorization", `Bearer ${env.GITHUB_TOKEN}`);

  // Only anonymous requests are cached. Authenticated tarballs may be private.
  const cacheable = !clientAuth;
  const cacheKey = new Request(url.toString(), { method: "GET" });
  if (cacheable && cache) {
    const hit = await cache.match(cacheKey);
    if (hit) return withHeaders(hit, cors, { "X-Relay-Cache": "hit" });
  }

  const res = await upstreamFetch(upstream, { headers, redirect: "follow" });

  if (res.status === 404) return json({ error: "repository or ref not found" }, 404, cors);
  if (res.status === 401 || res.status === 403) {
    const remaining = res.headers.get("x-ratelimit-remaining");
    const reset = res.headers.get("x-ratelimit-reset");
    return json(
      { error: "github refused the request", status: res.status, rateLimitRemaining: remaining, rateLimitReset: reset },
      res.status,
      cors,
    );
  }
  if (!res.ok || !res.body) return json({ error: "upstream failure", status: res.status }, 502, cors);

  const len = Number(res.headers.get("content-length") ?? 0);
  if (len > MAX_BYTES) return json({ error: "repository tarball exceeds 60 MB" }, 413, cors);

  const out = new Response(res.body, {
    status: 200,
    headers: {
      ...cors,
      "Content-Type": "application/gzip",
      "Cache-Control": "public, max-age=300",
      "X-Relay-Cache": "miss",
      "X-Relay-Upstream": upstream,
    },
  });

  if (cacheable && cache && ctx?.waitUntil) {
    ctx.waitUntil(cache.put(cacheKey, out.clone()));
  }
  return out;
}

function corsHeaders(origin, env) {
  const allowed = env?.ALLOWED_ORIGINS ?? "*";
  const allowOrigin = allowed === "*" ? "*" : allowed.split(",").map((s) => s.trim()).includes(origin) ? origin : "null";
  return {
    "Access-Control-Allow-Origin": allowOrigin,
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Expose-Headers": "X-Relay-Cache, X-Relay-Upstream, Content-Length",
    "Access-Control-Max-Age": "86400",
    Vary: "Origin",
  };
}

function json(body, status, cors) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

function withHeaders(res, ...sets) {
  const h = new Headers(res.headers);
  for (const set of sets) for (const [k, v] of Object.entries(set)) h.set(k, v);
  return new Response(res.body, { status: res.status, headers: h });
}
