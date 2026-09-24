const BASE = process.env.PAYPAL_BASE ?? "https://api-m.sandbox.paypal.com";

async function call(method, path, token, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  return res.json();
}

export const createPayment = (token, body) => call("POST", "/v1/payments/payment", token, body);
export const executePayment = (token, id, payerId) =>
  call("POST", `/v1/payments/payment/${id}/execute`, token, { payer_id: payerId });
export const refundSale = (token, saleId) => call("POST", `/v1/payments/sale/${saleId}/refund`, token, {});
export const getAuthorization = (token, id) => fetch(`https://api-m.paypal.com/v1/payments/authorization/${id}`, {
  headers: { Authorization: `Bearer ${token}` },
});
export const getOrder = (token, id) => call("GET", `/v1/payments/orders/${id}`, token);
export const getCapture = (token, id) => call("GET", `/v1/payments/capture/${id}`, token);
export const getRefund = (token, id) => call("GET", `/v1/payments/refund/${id}`, token);
