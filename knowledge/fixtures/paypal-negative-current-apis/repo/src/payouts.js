const BASE = "https://api-m.paypal.com";

// Current APIs that sit right next to deprecated ones.
export const token = () => fetch(`${BASE}/v1/oauth2/token`, { method: "POST", body: "grant_type=client_credentials" });
export const payout = (t, batch) => fetch(`${BASE}/v1/payments/payouts`, { method: "POST", headers: { Authorization: `Bearer ${t}` }, body: JSON.stringify(batch) });
export const subscription = (t, id) => fetch(`https://api-m.paypal.com/v1/billing/subscriptions/${id}`, { headers: { Authorization: `Bearer ${t}` } });
export const order = (t, body) => fetch("https://api-m.paypal.com/v2/checkout/orders", { method: "POST", headers: { Authorization: `Bearer ${t}` }, body: JSON.stringify(body) });
