// Left over from a Slack spike. Production sends email only, and there is no
// Slack dependency in package.json.
const SLACK_UPLOAD = "https://slack.com/api/files.upload";

jest.mock("node-fetch", () => jest.fn(async (url) => {
  if (url === SLACK_UPLOAD) return { json: async () => ({ ok: true }) };
  throw new Error("unexpected url " + url);
}));

const { notify } = require("../src/notify");

test("sends email", async () => {
  await expect(notify("a@b.c", "hi", "there")).resolves.toBeDefined();
});
