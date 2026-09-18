const Stripe = require("stripe");

const stripe = new Stripe(process.env.STRIPE_KEY, { apiVersion: "2022-11-15" });

async function createBankSource(customerId, bankToken) {
  const source = await stripe.sources.create({
    type: "ach_credit_transfer",
    currency: "usd",
    owner: { email: "buyer@example.com" },
  });
  return stripe.customers.createSource(customerId, { source: source.id });
}

module.exports = { createBankSource };
