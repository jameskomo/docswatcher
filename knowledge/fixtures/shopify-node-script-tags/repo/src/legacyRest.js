// The first version of the installer, still used by shops on the old plan.
import fetch from "node-fetch";

const API_VERSION = process.env.SHOPIFY_API_VERSION;

export async function createScriptTag(shop, token, src) {
  const res = await fetch(`https://${shop}/admin/api/${API_VERSION}/script_tags.json`, {
    method: "POST",
    headers: { "X-Shopify-Access-Token": token, "Content-Type": "application/json" },
    body: JSON.stringify({ script_tag: { event: "onload", src } }),
  });
  return res.json();
}

export async function updateScriptTag(shop, token, id, src) {
  const res = await fetch(`https://${shop}/admin/api/${API_VERSION}/script_tags/${id}.json`, {
    method: "PUT",
    headers: { "X-Shopify-Access-Token": token, "Content-Type": "application/json" },
    body: JSON.stringify({ script_tag: { id, src } }),
  });
  return res.json();
}
