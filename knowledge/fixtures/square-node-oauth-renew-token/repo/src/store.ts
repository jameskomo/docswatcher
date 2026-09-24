export interface Seller {
  id: string;
  accessToken: string;
  expiresAt: string;
}

const sellers = new Map<string, Seller>();

export async function loadSeller(id: string): Promise<Seller> {
  const seller = sellers.get(id);
  if (!seller) throw new Error(`unknown seller ${id}`);
  return seller;
}

export async function saveSeller(seller: Seller): Promise<void> {
  sellers.set(seller.id, seller);
}
