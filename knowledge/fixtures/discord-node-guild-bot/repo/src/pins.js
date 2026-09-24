const fetch = require("node-fetch");

const headers = { Authorization: `Bot ${process.env.DISCORD_TOKEN}` };

async function listPins(channelId) {
  const res = await fetch(`https://discord.com/api/v8/channels/${channelId}/pins`, { headers });
  return res.json();
}

async function pin(channelId, messageId) {
  return fetch(`https://discord.com/api/v8/channels/${channelId}/pins/${messageId}`, { method: "PUT", headers });
}

module.exports = { listPins, pin };
