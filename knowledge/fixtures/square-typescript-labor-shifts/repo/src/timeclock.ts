import { randomUUID } from "node:crypto";
import { SquareClient, SquareEnvironment } from "square";

const client = new SquareClient({
  token: process.env.SQUARE_ACCESS_TOKEN,
  environment: SquareEnvironment.Production,
});

export async function clockIn(locationId: string, teamMemberId: string) {
  const response = await client.labor.shifts.create({
    idempotencyKey: randomUUID(),
    shift: {
      locationId,
      teamMemberId,
      startAt: new Date().toISOString(),
    },
  });
  return response.shift;
}

export async function openShifts(locationId: string) {
  const response = await client.labor.shifts.search({
    query: { filter: { locationIds: [locationId], status: "OPEN" } },
  });
  return response.shifts ?? [];
}
