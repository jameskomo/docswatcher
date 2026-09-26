from google import genai

client = genai.Client()

def narrate(text: str):
    return client.models.generate_content(model="gemini-3.8-flash-tts", contents=text)

def narrate_cheap(text: str):
    return client.models.generate_content(model="gemini-3.8-flash-lite-tts", contents=text)

LIVE_MODEL = "gemini-3.8-live"
FALLBACK_LIVE_MODEL = "gemini-3.8-live-extended-thinking"
