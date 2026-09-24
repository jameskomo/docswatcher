import websockets

REALTIME_URL = "wss://contoso.openai.azure.com/openai/realtime?api-version=2025-04-01-preview&deployment=voice"


async def connect(api_key: str):
    return await websockets.connect(REALTIME_URL, additional_headers={"api-key": api_key, "OpenAI-Beta": "realtime=v1"})
