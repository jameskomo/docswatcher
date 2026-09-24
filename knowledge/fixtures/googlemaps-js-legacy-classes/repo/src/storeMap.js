import { Loader } from "@googlemaps/js-api-loader";

const loader = new Loader({ apiKey: process.env.MAPS_KEY, version: "weekly", libraries: ["places"] });

export async function initStoreMap(el, stores) {
  await loader.load();
  const map = new google.maps.Map(el, { center: stores[0].position, zoom: 11 });

  const markers = stores.map(
    (store) => new google.maps.Marker({ position: store.position, map, title: store.name })
  );

  const regions = new google.maps.KmlLayer({ url: "https://example.com/delivery-zones.kml", map });

  google.maps.event.addDomListener(window, "resize", () => map.panTo(stores[0].position));
  google.maps.event.addDomListenerOnce(el, "click", () => markers[0].setAnimation(null));

  return { map, markers, regions };
}
