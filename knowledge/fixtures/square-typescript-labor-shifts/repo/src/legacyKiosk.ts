// The in-store kiosk still runs on the legacy client that square 40+ ships
// under square/legacy, until its screens are rewritten.
import { Client, Environment } from "square/legacy";

const legacy = new Client({
  bearerAuthCredentials: { accessToken: process.env.SQUARE_ACCESS_TOKEN as string },
  environment: Environment.Production,
});

export async function kioskClockIn(locationId: string, teamMemberId: string, key: string) {
  const { result } = await legacy.laborApi.createShift({
    idempotencyKey: key,
    shift: { locationId, teamMemberId, startAt: new Date().toISOString() },
  });
  return result.shift;
}

export async function kioskOpenShifts(locationId: string) {
  const { result } = await legacy.laborApi.searchShifts({
    query: { filter: { locationIds: [locationId], status: "OPEN" } },
  });
  return result.shifts ?? [];
}
