import json
import os

import aiohttp

# Written for the original bot and never upgraded.
GATEWAY_URL = "wss://gateway.discord.gg/?v=6&encoding=json"
REST_BASE = "https://discord.com/api/v7"


async def identify():
    async with aiohttp.ClientSession() as session:
        async with session.ws_connect(GATEWAY_URL) as ws:
            await ws.send_str(json.dumps({"op": 2, "d": {"token": os.environ["DISCORD_TOKEN"], "properties": {}}}))
            return await ws.receive_json()


async def create_role(guild_id: str, name: str):
    async with aiohttp.ClientSession() as session:
        url = f"{REST_BASE}/guilds/{guild_id}/roles"
        headers = {"Authorization": f"Bot {os.environ['DISCORD_TOKEN']}"}
        async with session.post(url, json={"name": name}, headers=headers) as resp:
            return await resp.json()
