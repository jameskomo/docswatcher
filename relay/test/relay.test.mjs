import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/worker.js";

const noCache = undefined;
const ctx = { waitUntil() {} };

function fakeFetch(status, body = "", headers = {}) {
  const f = async (url, init) => {
    f.last = { url, init };
    return new Response(body, { status, headers });
  };
  return f;
}

test("preflight returns CORS headers", async () => {
  const res = await handle(new Request("https://r.test/tarball/a/b", { method: "OPTIONS", headers: { Origin: "https://x.test" } }), {}, ctx, fakeFetch(200), noCache);
  assert.equal(res.status, 204);
  // An unset ALLOWED_ORIGINS denies rather than opening the relay to every page. The relay
  // lends its own GitHub credential on the anonymous branch, so a wildcard default meant any
  // third-party page could drive a credentialed fetch from a visitor's browser.
  assert.equal(res.headers.get("Access-Control-Allow-Origin"), "null");
});

test("health endpoint", async () => {
  const res = await handle(new Request("https://r.test/health"), {}, ctx, fakeFetch(200), noCache);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { ok: true, service: "docswatcher-relay" });
});

test("rejects malformed owner", async () => {
  const res = await handle(new Request("https://r.test/tarball/bad%20owner/repo"), {}, ctx, fakeFetch(200), noCache);
  assert.equal(res.status, 400);
});

test("streams tarball with gzip content type and relay headers", async () => {
  const f = fakeFetch(200, "gzipbytes", { "content-length": "9" });
  const res = await handle(new Request("https://r.test/tarball/stripe/stripe-java/master", { headers: { Origin: "https://x.test" } }), {}, ctx, f, noCache);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("Content-Type"), "application/gzip");
  // An unset ALLOWED_ORIGINS denies rather than opening the relay to every page. The relay
  // lends its own GitHub credential on the anonymous branch, so a wildcard default meant any
  // third-party page could drive a credentialed fetch from a visitor's browser.
  assert.equal(res.headers.get("Access-Control-Allow-Origin"), "null");
  assert.equal(res.headers.get("X-Relay-Cache"), "miss");
  assert.equal(await res.text(), "gzipbytes");
  assert.equal(f.last.url, "https://api.github.com/repos/stripe/stripe-java/tarball/master");
  assert.equal(f.last.init.redirect, "follow");
});

test("forwards client Authorization and skips cache", async () => {
  const f = fakeFetch(200, "x", { "content-length": "1" });
  const cache = { match: async () => { throw new Error("must not read cache"); }, put: async () => {} };
  const res = await handle(new Request("https://r.test/tarball/o/r", { headers: { Authorization: "Bearer t" } }), {}, ctx, f, cache);
  assert.equal(res.status, 200);
  assert.equal(f.last.init.headers.get("Authorization"), "Bearer t");
});

test("uses GITHUB_TOKEN from env when client sends none", async () => {
  const f = fakeFetch(200, "x", { "content-length": "1" });
  await handle(new Request("https://r.test/tarball/o/r"), { GITHUB_TOKEN: "envtok" }, ctx, f, noCache);
  assert.equal(f.last.init.headers.get("Authorization"), "Bearer envtok");
});

test("maps 404 and rate limit responses to JSON errors", async () => {
  const nf = await handle(new Request("https://r.test/tarball/o/missing"), {}, ctx, fakeFetch(404), noCache);
  assert.equal(nf.status, 404);
  const rl = await handle(new Request("https://r.test/tarball/o/r"), {}, ctx, fakeFetch(403, "", { "x-ratelimit-remaining": "0", "x-ratelimit-reset": "1" }), noCache);
  assert.equal(rl.status, 403);
  assert.equal((await rl.json()).rateLimitRemaining, "0");
});

test("refuses oversized tarballs", async () => {
  const res = await handle(new Request("https://r.test/tarball/o/r"), {}, ctx, fakeFetch(200, "x", { "content-length": String(61 * 1024 * 1024) }), noCache);
  assert.equal(res.status, 413);
});

test("serves from cache when present", async () => {
  const cached = new Response("cached", { status: 200, headers: { "Content-Type": "application/gzip" } });
  const cache = { match: async () => cached, put: async () => {} };
  const res = await handle(new Request("https://r.test/tarball/o/r"), {}, ctx, fakeFetch(500), cache);
  assert.equal(res.headers.get("X-Relay-Cache"), "hit");
  assert.equal(await res.text(), "cached");
});

test("a response fetched with the relay's own token is never cached or marked public", async () => {
  // The defect this pins: cacheability used to be !clientAuth, which is exactly the branch
  // where the relay attached its OWN credential to a repository the caller named.
  let put = null;
  const cache = { match: async () => null, put: async (k, v) => { put = { k, v }; } };
  const ctx = { waitUntil: (p) => p };
  const res = await handle(
    new Request("https://r.test/tarball/o/private"),
    { GITHUB_TOKEN: "envtok" },
    ctx,
    fakeFetch(200, "gzipbytes", { "content-length": "9" }),
    cache,
  );
  assert.equal(res.status, 200);
  assert.equal(put, null, "a relay-credentialed response must not enter the shared cache");
  assert.equal(res.headers.get("Cache-Control"), "private, no-store");
  assert.match(res.headers.get("Vary"), /Authorization/);
});
