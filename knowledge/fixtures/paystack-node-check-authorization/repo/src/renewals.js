const { Paystack } = require("@paystack/paystack-sdk");

const paystack = new Paystack(process.env.PAYSTACK_SECRET_KEY);

// Before charging a saved card for a renewal, ask Paystack whether the card
// can cover the amount, and skip the charge if it cannot.
async function renew(subscription) {
  const check = await paystack.transaction.checkAuthorization({
    email: subscription.email,
    amount: subscription.amountKobo,
    authorization_code: subscription.authorizationCode,
  });
  if (!check.status) {
    return { renewed: false, reason: check.message };
  }
  const charge = await paystack.transaction.chargeAuthorization({
    email: subscription.email,
    amount: subscription.amountKobo,
    authorization_code: subscription.authorizationCode,
  });
  return { renewed: charge.status, reference: charge.data.reference };
}

module.exports = { renew };
