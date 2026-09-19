const { WebClient } = require("@slack/web-api");
const fetch = require("node-fetch");

const web = new WebClient(process.env.SLACK_BOT_TOKEN);

async function shareReport(channel, filename, buffer) {
  return web.files.upload({
    channels: channel,
    filename,
    file: buffer,
    initial_comment: "Nightly report",
  });
}

async function shareViaRawApi(token, channel, buffer) {
  const res = await fetch("https://slack.com/api/files.upload", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: buffer,
  });
  return res.json();
}

module.exports = { shareReport, shareViaRawApi };
