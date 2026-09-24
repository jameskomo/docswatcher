async function createOrder(instanceUrl, accessToken, order) {
  const res = await fetch(`${instanceUrl}/services/data/v62.0/sobjects/Order`, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
    body: JSON.stringify(order),
  });
  return res.json();
}

module.exports = { createOrder };
