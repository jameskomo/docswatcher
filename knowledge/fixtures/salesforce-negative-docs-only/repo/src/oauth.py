import requests

TOKEN_URL = "https://login.salesforce.com/services/oauth2/token"


def client_credentials(key: str, secret: str) -> dict:
    data = {"grant_type": "client_credentials", "client_id": key, "client_secret": secret}
    return requests.post(TOKEN_URL, data=data, timeout=30).json()
