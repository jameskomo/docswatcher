// The payroll export reads closed shifts straight from the REST API, because it
// runs in a worker without the SDK bundled.
const SHIFT_URL = "https://connect.squareup.com/v2/labor/shifts";

export async function closedShift(shiftId: string, token: string) {
  const res = await fetch(`${SHIFT_URL}/${shiftId}`, {
    headers: { Authorization: `Bearer ${token}`, "Square-Version": "2025-04-16" },
  });
  if (!res.ok) throw new Error(`shift ${shiftId}: ${res.status}`);
  return res.json();
}
