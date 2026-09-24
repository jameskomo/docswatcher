export async function initMap(el: HTMLElement, position: google.maps.LatLngLiteral) {
  const { Map } = (await google.maps.importLibrary("maps")) as google.maps.MapsLibrary;
  const { AdvancedMarkerElement } = (await google.maps.importLibrary("marker")) as google.maps.MarkerLibrary;
  const map = new Map(el, { center: position, zoom: 14, mapId: "store-map" });
  const marker = new AdvancedMarkerElement({ map, position, title: "Our store" });
  marker.addEventListener("gmp-click", () => map.panTo(position));
  return map;
}

export async function route(origin: string, destination: string, key: string) {
  const res = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
    method: "POST",
    headers: { "X-Goog-Api-Key": key, "X-Goog-FieldMask": "routes.duration" },
    body: JSON.stringify({ origin: { address: origin }, destination: { address: destination } }),
  });
  return res.json();
}
