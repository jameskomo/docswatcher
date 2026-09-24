import os

import requests

from dispatch.routing import gmaps

DETAILS_URL = "https://maps.googleapis.com/maps/api/place/details/json"
PHOTO_URL = "https://maps.googleapis.com/maps/api/place/photo"


def pharmacies_near(lat: float, lng: float):
    return gmaps.places_nearby(location=(lat, lng), radius=1500, type="pharmacy")


def resolve(name: str):
    return gmaps.find_place(name, "textquery", fields=["place_id", "name"])


def suggest(prefix: str):
    return gmaps.places_autocomplete(prefix, components={"country": ["ke"]})


def photo_url(reference: str, key: str) -> str:
    return f"{PHOTO_URL}?maxwidth=400&photo_reference={reference}&key={key}"


def details(place_id: str):
    params = {"place_id": place_id, "fields": "name,formatted_phone_number", "key": os.environ["MAPS_API_KEY"]}
    return requests.get(DETAILS_URL, params=params, timeout=10).json()
