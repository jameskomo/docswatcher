export async function owners(token: string) {
  const res = await fetch("https://api.hubapi.com/owners/v2/owners", {
    headers: { Authorization: `Bearer ${token}` },
  });
  return res.json();
}
