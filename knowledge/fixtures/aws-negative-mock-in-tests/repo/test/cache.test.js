// A migration test kept from when this service used S3. The production code
// no longer imports the SDK and package.json no longer depends on it.
jest.mock("aws-sdk/clients/s3", () => ({ putObject: jest.fn() }));

const { put } = require("../src/cache");

test("writes to disk", async () => {
  await expect(put("/tmp", "a.txt", "x")).resolves.toBeUndefined();
});
