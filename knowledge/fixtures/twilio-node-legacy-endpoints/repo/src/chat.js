const fetch = require("node-fetch");

// The agent console still reads channels from Programmable Chat. The
// ip-messaging host is the original name for the same API.
const CHAT_SERVICES = "https://chat.twilio.com/v2/Services";
const LEGACY_CHAT_SERVICES = "https://ip-messaging.twilio.com/v2/Services";

async function listChannels(serviceSid, auth) {
  const res = await fetch(`${CHAT_SERVICES}/${serviceSid}/Channels`, {
    headers: { Authorization: auth },
  });
  return res.json();
}

module.exports = { listChannels, LEGACY_CHAT_SERVICES };
