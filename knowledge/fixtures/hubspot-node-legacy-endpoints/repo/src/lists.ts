import { Client } from "@hubspot/api-client";

const hubspot = new Client({ accessToken: process.env.HUBSPOT_TOKEN });

export async function staticLists() {
  const res = await hubspot.apiRequest({ method: "GET", path: "/contacts/v1/lists/static" });
  return (await res.json()).lists;
}

export async function addToList(listId: number, vids: number[]) {
  return hubspot.apiRequest({
    method: "POST",
    path: `/contacts/v1/lists/${listId}/add`,
    body: { vids },
  });
}

export async function activeLists() {
  return hubspot.apiRequest({ method: "GET", path: "/contacts/v1/lists/dynamic" });
}

export async function listsById(ids: number[]) {
  const query = ids.map((id) => `listId=${id}`).join("&");
  return hubspot.apiRequest({ method: "GET", path: `/contacts/v1/lists/batch?${query}` });
}

export async function everyone() {
  // Still served after the sunset, without list memberships.
  return hubspot.apiRequest({ method: "GET", path: "/contacts/v1/lists/all/contacts/all" });
}
