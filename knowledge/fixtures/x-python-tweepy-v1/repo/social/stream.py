import json
import os

import requests
from requests_oauthlib import OAuth1

STREAM_URL = "https://stream.twitter.com/1.1/statuses/filter.json"
OEMBED_URL = "https://api.twitter.com/1.1/statuses/oembed.json"


def _auth() -> OAuth1:
    return OAuth1(
        os.environ["X_CONSUMER_KEY"],
        os.environ["X_CONSUMER_SECRET"],
        os.environ["X_ACCESS_TOKEN"],
        os.environ["X_ACCESS_SECRET"],
    )


def follow_keywords(keywords, on_post):
    with requests.post(STREAM_URL, data={"track": ",".join(keywords)}, auth=_auth(), stream=True) as r:
        for line in r.iter_lines():
            if line:
                on_post(json.loads(line))


def embed_html(post_url: str) -> str:
    return requests.get(OEMBED_URL, params={"url": post_url, "omit_script": "true"}).json()["html"]
