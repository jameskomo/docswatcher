# Each integration was written against the version current at the time and never moved.
import requests

CATALOG_URL = "https://graph.facebook.com/v17.0/me/accounts"
LEADS_URL = "https://graph.facebook.com/v18.0/me/accounts"
PAGE_POSTS_URL = "https://graph.facebook.com/v19.0/me/accounts"
COMMENTS_URL = "https://graph.facebook.com/v20.0/me/accounts"
INSTAGRAM_URL = "https://graph.instagram.com/v22.0/me/media"


def fetch(url: str, token: str) -> dict:
    return requests.get(url, params={"access_token": token}).json()
