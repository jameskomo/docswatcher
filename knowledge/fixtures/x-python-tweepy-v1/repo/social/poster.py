import os

import tweepy

auth = tweepy.OAuth1UserHandler(
    os.environ["X_CONSUMER_KEY"],
    os.environ["X_CONSUMER_SECRET"],
    os.environ["X_ACCESS_TOKEN"],
    os.environ["X_ACCESS_SECRET"],
)
api = tweepy.API(auth)
client = tweepy.Client(
    consumer_key=os.environ["X_CONSUMER_KEY"],
    consumer_secret=os.environ["X_CONSUMER_SECRET"],
    access_token=os.environ["X_ACCESS_TOKEN"],
    access_token_secret=os.environ["X_ACCESS_SECRET"],
)


def post_with_image(text: str, image_path: str) -> str:
    media = api.media_upload(filename=image_path)
    response = client.create_tweet(text=text, media_ids=[media.media_id])
    return response.data["id"]


def mentions_of(handle: str, limit: int = 50):
    return api.search_tweets(q=f"@{handle} -filter:retweets", count=limit, result_type="recent")
