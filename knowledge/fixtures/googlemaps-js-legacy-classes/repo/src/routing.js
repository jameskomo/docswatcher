export function routeToStore(map, origin, store) {
  const service = new google.maps.DirectionsService();
  const renderer = new google.maps.DirectionsRenderer({ map });
  service.route(
    { origin, destination: store.position, travelMode: google.maps.TravelMode.DRIVING },
    (result, status) => {
      if (status === "OK") renderer.setDirections(result);
    }
  );
}

export function travelTimes(origins, stores, callback) {
  const matrix = new google.maps.DistanceMatrixService();
  matrix.getDistanceMatrix(
    { origins, destinations: stores.map((s) => s.position), travelMode: "DRIVING" },
    callback
  );
}

export function nearbyCoffee(map, location, callback) {
  const places = new google.maps.places.PlacesService(map);
  places.nearbySearch({ location, radius: 500, type: "cafe" }, callback);
}
