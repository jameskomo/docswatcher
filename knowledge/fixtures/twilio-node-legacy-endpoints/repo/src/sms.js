const twilio = require("twilio");

const client = twilio(process.env.TWILIO_SID, process.env.TWILIO_TOKEN);

async function sendSms(to, body) {
  return client.messages.create({ to, body, from: process.env.TWILIO_FROM });
}

module.exports = { sendSms };
