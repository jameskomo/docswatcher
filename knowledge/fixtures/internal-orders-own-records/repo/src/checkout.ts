import { OrdersClient } from "@acme/orders-client";

const orders = new OrdersClient({ token: process.env.ORDERS_TOKEN });

export async function checkout(cart: { items: string[]; total: number }) {
  const order = await orders.createOrder({ items: cart.items, amount: cart.total });
  return order.id;
}

export async function orderStatus(id: string) {
  const res = await fetch(`https://orders.internal.acme.dev/v1/orders/${id}`);
  return res.json();
}

export async function refund(id: string) {
  await fetch("https://orders.internal.acme.dev/v2/refunds", { method: "POST", body: JSON.stringify({ id }) });
}
