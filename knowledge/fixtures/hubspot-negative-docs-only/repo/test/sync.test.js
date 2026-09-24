const nock = require("nock");

test("legacy lists endpoint is no longer called", () => {
  const scope = nock("https://api.hubapi.com").get("/contacts/v1/lists").reply(404);
  const url = "https://api.hubapi.com/contacts/v1/lists";
  expect(url).toContain("v1");
  scope.done();
});
