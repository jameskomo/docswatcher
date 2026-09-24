const PAYMENTS_SERVICE = process.env.PAYMENTS_SERVICE_URL;

async function refund(orderId) {
  const res = await fetch(`${PAYMENTS_SERVICE}/refunds`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ orderId }),
  });
  return res.json();
}

module.exports = { refund };
