const Stripe = require("stripe");
const stripe = new Stripe(process.env.STRIPE_KEY);

async function charge(customerId, amount) {
  return stripe.paymentIntents.create({ customer: customerId, amount, currency: "usd", confirm: true });
}

module.exports = { charge };
