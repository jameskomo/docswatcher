import os

import requests

BEARER = os.environ["X_BEARER_TOKEN"]
WEBHOOK_ID = os.environ["X_WEBHOOK_ID"]


def replay_missed_events(from_date: str, to_date: str) -> int:
    url = f"https://api.x.com/2/account_activity/replay/webhooks/{WEBHOOK_ID}/subscriptions/all"
    r = requests.post(
        url,
        params={"from_date": from_date, "to_date": to_date},
        headers={"Authorization": f"Bearer {BEARER}"},
    )
    return r.status_code


def list_webhooks():
    r = requests.get("https://api.x.com/2/webhooks", headers={"Authorization": f"Bearer {BEARER}"})
    return r.json()
