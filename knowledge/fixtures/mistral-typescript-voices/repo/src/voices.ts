const VOICES_URL = "https://api.mistral.ai/v1/audio/voices";

export async function listVoices(offset = 0): Promise<string[]> {
  const res = await fetch(`${VOICES_URL}?limit=50&offset=${offset}`, {
    headers: { Authorization: `Bearer ${process.env.MISTRAL_API_KEY}` },
  });
  const body = await res.json();
  return body.items.map((v: { id: string }) => v.id);
}
