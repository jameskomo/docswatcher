export async function dealContacts(token: string, dealId: string) {
  const url = `https://api.hubapi.com/crm/v4/objects/deals/${dealId}/associations/contacts`;
  return (await fetch(url, { headers: { Authorization: `Bearer ${token}` } })).json();
}

export async function contactsV3(token: string) {
  const url = "https://api.hubapi.com/crm/v3/objects/contacts?limit=100";
  return (await fetch(url, { headers: { Authorization: `Bearer ${token}` } })).json();
}

export async function contactsDateVersioned(token: string) {
  const url = "https://api.hubapi.com/crm/objects/2026-09/contacts";
  return (await fetch(url, { headers: { Authorization: `Bearer ${token}` } })).json();
}
