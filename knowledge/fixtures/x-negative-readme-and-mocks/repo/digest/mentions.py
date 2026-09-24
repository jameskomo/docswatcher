import os

import requests

SEARCH_URL = "https://api.x.com/2/tweets/search/recent"


def recent_mentions(handle: str):
    r = requests.get(
        SEARCH_URL,
        params={"query": f"@{handle} -is:retweet", "tweet.fields": "created_at,author_id"},
        headers={"Authorization": f"Bearer {os.environ['X_BEARER_TOKEN']}"},
    )
    r.raise_for_status()
    return r.json().get("data", [])
