import requests

GRAPH_URL = "https://graph.microsoft.com/v1.0/users"
api_version = "v1.0"


def users(token: str):
    return requests.get(GRAPH_URL, headers={"Authorization": f"Bearer {token}"}).json()["value"]
