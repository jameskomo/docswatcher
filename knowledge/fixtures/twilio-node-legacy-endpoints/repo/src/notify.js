const fetch = require("node-fetch");

// Notify carried push registration and bulk SMS fan-out for this service.
const NOTIFY_SERVICES = "https://notify.twilio.com/v1/Services";
const NOTIFY_CREDENTIALS = "https://notify.twilio.com/v1/Credentials";

async function sendPush(serviceSid, identity, body, auth) {
  const res = await fetch(`${NOTIFY_SERVICES}/${serviceSid}/Notifications`, {
    method: "POST",
    headers: { Authorization: auth },
    body: new URLSearchParams({ Identity: identity, Body: body }),
  });
  return res.json();
}

module.exports = { sendPush, NOTIFY_CREDENTIALS };
