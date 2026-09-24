const DEAL_PIPELINES = "https://api.hubapi.com/crm-pipelines/v1/pipelines/deals";
const DEFAULT_TICKETS = "https://api.hubapi.com/crm-pipelines/v1/pipelines/tickets/default";

export async function dealStages(token: string) {
  const res = await fetch(DEAL_PIPELINES, { headers: { Authorization: `Bearer ${token}` } });
  return res.json();
}

export async function pipelinesFor(objectType: "deals" | "tickets", token: string) {
  const url = `https://api.hubapi.com/crm-pipelines/v1/pipelines/${objectType}`;
  return (await fetch(url, { headers: { Authorization: `Bearer ${token}` } })).json();
}

export async function ticketPipeline(token: string) {
  const res = await fetch(DEFAULT_TICKETS, { headers: { Authorization: `Bearer ${token}` } });
  return res.json();
}
