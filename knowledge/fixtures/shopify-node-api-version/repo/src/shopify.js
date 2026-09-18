const fetch = require("node-fetch");

async function listProducts(shop, token) {
  const url = `https://${shop}/admin/api/2025-10/products.json?limit=250`;
  const res = await fetch(url, { headers: { "X-Shopify-Access-Token": token } });
  return res.json();
}

module.exports = { listProducts };
