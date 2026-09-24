const CONNECTORS_URL = "https://api.cohere.com/v1/connectors";

export async function listConnectors(apiKey: string) {
  const res = await fetch(CONNECTORS_URL, { headers: { Authorization: `Bearer ${apiKey}` } });
  return res.json();
}
