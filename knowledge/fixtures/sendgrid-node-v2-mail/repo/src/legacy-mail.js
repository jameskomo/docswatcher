const fetch = require("node-fetch");

// Transactional mail still goes through the v2 Web API on this path.
const V2_MAIL_JSON = "https://api.sendgrid.com/api/mail.send.json";
const V2_MAIL_XML = "https://api.sendgrid.com/api/mail.send.xml";

async function sendLegacy(to, subject, text) {
  const body = new URLSearchParams({ to, subject, text, from: "noreply@example.com" });
  const res = await fetch(V2_MAIL_JSON, {
    method: "POST",
    headers: { Authorization: `Bearer ${process.env.SENDGRID_KEY}` },
    body,
  });
  return res.json();
}

module.exports = { sendLegacy, V2_MAIL_XML };
