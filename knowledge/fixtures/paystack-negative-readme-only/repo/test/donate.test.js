const { donationLink } = require("../src/donate");

// A hand-rolled double in the shape of the Paystack SDK, kept from the days
// the site pre-checked recurring donors. Nothing imports the SDK itself.
const paystack = {
  transaction: {
    checkAuthorization: jest.fn().mockResolvedValue({ status: true }),
  },
};

test("donation link points at the payment page", async () => {
  await paystack.transaction.checkAuthorization({ email: "a@example.com", amount: 5000 });
  expect(donationLink("general")).toMatch(/^https:\/\/paystack\.com\/pay\//);
});
