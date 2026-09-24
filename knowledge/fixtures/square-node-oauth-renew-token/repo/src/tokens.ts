import { Client, Environment } from "square";
import { loadSeller, saveSeller } from "./store";

const client = new Client({
  environment: Environment.Production,
  additionalHeaders: { Authorization: `Client ${process.env.SQUARE_APP_SECRET}` },
});

const CLIENT_ID = process.env.SQUARE_APP_ID as string;

// Nightly job: keep every seller's token alive before it expires.
export async function renewSellerToken(sellerId: string): Promise<void> {
  const seller = await loadSeller(sellerId);
  const { result } = await client.oAuthApi.renewToken(CLIENT_ID, {
    accessToken: seller.accessToken,
  });
  await saveSeller({ ...seller, accessToken: result.accessToken!, expiresAt: result.expiresAt! });
}
