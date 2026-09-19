import os
import httpx

# Some callers still talk to the REST surface directly rather than via the SDK.
BASE = "https://generativelanguage.googleapis.com/v1beta/models"


def list_models():
    key = os.environ["GOOGLE_API_KEY"]
    return httpx.get(BASE, headers={"x-goog-api-key": key}).json()
