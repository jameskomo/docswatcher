import os

import requests

API = "https://discord.com/api/v10"


def pinned_messages(channel_id: str):
    url = f"https://discord.com/api/v10/channels/{channel_id}/messages/pins"
    headers = {"Authorization": f"Bot {os.environ['DISCORD_TOKEN']}"}
    r = requests.get(url, params={"limit": 50}, headers=headers)
    r.raise_for_status()
    return r.json()


def guild(guild_id: str):
    return requests.get(f"https://discord.com/api/v10/guilds/{guild_id}", headers={"Authorization": f"Bot {os.environ['DISCORD_TOKEN']}"}).json()
