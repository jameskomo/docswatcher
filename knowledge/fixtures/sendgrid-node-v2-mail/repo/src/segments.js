const fetch = require("node-fetch");

// Marketing segments were created through the original contactdb surface.
const SEGMENTS = "https://api.sendgrid.com/v3/contactdb/segments";

async function listSegments() {
  const res = await fetch(SEGMENTS, {
    headers: { Authorization: `Bearer ${process.env.SENDGRID_KEY}` },
  });
  return res.json();
}

module.exports = { listSegments };
