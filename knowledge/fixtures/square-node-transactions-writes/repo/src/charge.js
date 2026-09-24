const fetch = require("node-fetch");

const HEADERS = {
  Authorization: `Bearer ${process.env.SQUARE_ACCESS_TOKEN}`,
  "Content-Type": "application/json",
  "Square-Version": "2019-08-14",
};

async function chargeCard(locationId, nonce, amount, idempotencyKey) {
  const res = await fetch(`https://connect.squareup.com/v2/locations/${locationId}/transactions`, {
    method: "POST",
    headers: HEADERS,
    body: JSON.stringify({
      idempotency_key: idempotencyKey,
      card_nonce: nonce,
      amount_money: { amount, currency: "USD" },
      delay_capture: true,
    }),
  });
  return res.json();
}

async function refundTender(locationId, transactionId, tenderId, amount, idempotencyKey) {
  const url = `https://connect.squareup.com/v2/locations/${locationId}/transactions/${transactionId}/refund`;
  const res = await fetch(url, {
    method: "POST",
    headers: HEADERS,
    body: JSON.stringify({
      idempotency_key: idempotencyKey,
      tender_id: tenderId,
      amount_money: { amount, currency: "USD" },
    }),
  });
  return res.json();
}

module.exports = { chargeCard, refundTender };
