import os

import requests

BASE = "https://contoso.openai.azure.com/openai"
HEADERS = {"api-key": os.environ.get("AZURE_OPENAI_API_KEY", "")}


def create_tutor() -> str:
    r = requests.post(
        "https://contoso.openai.azure.com/openai/assistants?api-version=2024-05-01-preview",
        headers=HEADERS,
        json={"model": "tutor", "instructions": "You are a patient maths tutor."},
    )
    return r.json()["id"]


def start_thread() -> str:
    r = requests.post("https://contoso.openai.azure.com/openai/threads?api-version=2024-05-01-preview", headers=HEADERS)
    return r.json()["id"]
