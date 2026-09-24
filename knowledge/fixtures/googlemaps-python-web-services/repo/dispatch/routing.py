import os

import googlemaps

gmaps = googlemaps.Client(key=os.environ["MAPS_API_KEY"])


def eta(origin: str, destination: str) -> int:
    legs = gmaps.directions(origin, destination, mode="driving")[0]["legs"]
    return sum(leg["duration"]["value"] for leg in legs)


def matrix(origins: list[str], destinations: list[str]):
    return gmaps.distance_matrix(origins, destinations, mode="driving")


def geocode(address: str):
    return gmaps.geocode(address)
