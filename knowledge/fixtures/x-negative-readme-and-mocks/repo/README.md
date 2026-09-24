# Mentions digest

Collects mentions of the brand account through the X API v2 recent search
endpoint and emails a daily digest.

History: the first version polled https://api.twitter.com/1.1/search/tweets.json
and streamed from https://stream.twitter.com/1.1/statuses/filter.json. Both were
replaced by the v2 endpoints in 2024.
