import { Client } from "@googlemaps/google-maps-services-js";

const client = new Client({});
const key = process.env.MAPS_API_KEY!;

export async function quote(origin: string, destination: string) {
  const route = await client.directions({ params: { origin, destination, key } });
  const matrix = await client.distancematrix({
    params: { origins: [origin], destinations: [destination], key },
  });
  return { route: route.data.routes[0], matrix: matrix.data.rows };
}

export async function suggestPickupPoints(location: { lat: number; lng: number }) {
  const nearby = await client.placesNearby({ params: { location, radius: 800, key } });
  const first = nearby.data.results[0];
  const details = await client.placeDetails({ params: { place_id: first.place_id!, key } });
  return details.data.result;
}

export async function search(query: string) {
  const text = await client.textSearch({ params: { query, key } });
  const exact = await client.findPlaceFromText({
    params: { input: query, inputtype: "textquery" as any, key },
  });
  return { text: text.data.results, exact: exact.data.candidates };
}
