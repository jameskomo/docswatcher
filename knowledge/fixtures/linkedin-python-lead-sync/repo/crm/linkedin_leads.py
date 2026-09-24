import os

import requests

API = "https://api.linkedin.com/rest"
HEADERS = {
    "Authorization": f"Bearer {os.environ['LINKEDIN_TOKEN']}",
    "LinkedIn-Version": "202405",
    "X-Restli-Protocol-Version": "2.0.0",
}


def form_responses(account_id: int):
    r = requests.get(
        "https://api.linkedin.com/rest/adFormResponses",
        params={"q": "account", "account": f"urn:li:sponsoredAccount:{account_id}"},
        headers=HEADERS,
    )
    r.raise_for_status()
    return r.json()["elements"]


def register_webhook(account_id: int, url: str):
    body = {"key": {"sponsoredEntity": f"urn:li:sponsoredAccount:{account_id}"}, "status": "ACTIVE", "url": url}
    return requests.post("https://api.linkedin.com/rest/leadNotificationUrls", json=body, headers=HEADERS)


def campaigns(account_id: int):
    r = requests.get(
        f"https://api.linkedin.com/rest/adAccounts/{account_id}/adCampaigns",
        params={"q": "search"},
        headers=HEADERS,
    )
    return r.json()["elements"]
