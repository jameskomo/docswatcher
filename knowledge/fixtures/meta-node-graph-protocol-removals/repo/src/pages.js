const GRAPH = "https://graph.facebook.com/v26.0";

export async function pageSummaries(pageIds, token) {
  const res = await fetch(`https://graph.facebook.com/v26.0/?ids=${pageIds.join(",")}&access_token=${token}`);
  return res.json();
}

export async function orders(pageId, token) {
  const res = await fetch(`${GRAPH}/${pageId}/commerce_orders?state=CREATED&access_token=${token}`);
  return (await res.json()).data;
}
