const fetch = require("node-fetch");

const apiKey = process.env.MAILCHIMP_API_KEY;
const dc = apiKey.split("-")[1];

async function subscribe(listId, email) {
  const res = await fetch(`https://${dc}.api.mailchimp.com/2.0/lists/subscribe.json`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ apikey: apiKey, id: listId, email: { email }, double_optin: true }),
  });
  return res.json();
}

async function exportList(listId) {
  const res = await fetch(`https://${dc}.api.mailchimp.com/export/1.0/list/?apikey=${apiKey}&id=${listId}`);
  return res.text();
}

module.exports = { subscribe, exportList };
