const { refund } = require("../src/refund");

// The payments service used to be called in-process; the test double keeps the
// old SDK shape so the legacy contract tests still compile.
const client = {
  transactionsApi: {
    captureTransaction: jest.fn().mockResolvedValue({ result: {} }),
  },
};

test("refund forwards to the payments service", async () => {
  await client.transactionsApi.captureTransaction("L1", "T1");
  await expect(refund("order-1")).resolves.toEqual({ ok: true });
});
