from unittest import mock

from digest import mentions

# Kept to prove the digest no longer touches the retired v1.1 search.
LEGACY_SEARCH = "https://api.twitter.com/1.1/search/tweets.json"


def test_uses_v2_search():
    with mock.patch("digest.mentions.requests.get") as get:
        get.return_value.json.return_value = {"data": []}
        mentions.recent_mentions("acme")
        assert get.call_args[0][0] != LEGACY_SEARCH
