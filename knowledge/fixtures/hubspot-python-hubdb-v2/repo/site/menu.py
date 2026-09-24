import os

import requests
from hubspot import HubSpot

TOKEN = os.environ["HUBSPOT_TOKEN"]
client = HubSpot(access_token=TOKEN)
HUBDB = "https://api-eu1.hubapi.com/hubdb/api/v2/tables"


def menu_rows(table_id: int):
    res = requests.get(f"{HUBDB}/{table_id}/rows", headers={"Authorization": f"Bearer {TOKEN}"}, timeout=10)
    return res.json()["objects"]


def tables():
    return client.api_request({"method": "GET", "path": "/hubdb/api/v2/tables"}).json()
