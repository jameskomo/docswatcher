const config = require("../release/config");

// A stub of the social service's LinkedIn client, kept for the contract test.
const linkedinHeaders = { "Linkedin-Version": "202405" };

test("release number is YYYYMM", () => {
  expect(config.releaseVersion).toMatch(/^\d{6}$/);
  expect(linkedinHeaders["Linkedin-Version"]).toBe(config.releaseVersion);
});
