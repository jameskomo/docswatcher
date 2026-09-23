import { EmailMessage } from "cloudflare:email";
import { compose } from "./message.js";

/** POST / with the app's bearer token and one early-access request as JSON. Nothing else. */
export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
    if (!env.TOKEN || !(await same(request.headers.get("Authorization") ?? "", `Bearer ${env.TOKEN}`))) {
      return new Response("Unauthorized", { status: 401 });
    }
    let lead;
    try {
      lead = await request.json();
    } catch {
      return new Response("Bad request", { status: 400 });
    }
    await env.MAIL.send(new EmailMessage(env.FROM, env.TO, compose(lead, { from: env.FROM, to: env.TO })));
    return new Response("Sent", { status: 202 });
  },
};

/** Compares digests, so the time taken says nothing about how much of the token matched. */
async function same(a, b) {
  const digest = async (s) => new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
  const [x, y] = await Promise.all([digest(a), digest(b)]);
  return x.every((v, i) => v === y[i]);
}
