from google import genai
from google.genai import types

client = genai.Client()

CHAT_MODEL = "gemini-2.0-flash"
IMAGE_MODEL = "gemini-2.5-flash-image"


def answer(question: str) -> str:
    response = client.models.generate_content(
        model=CHAT_MODEL,
        contents=question,
        config=types.GenerateContentConfig(max_output_tokens=512),
    )
    return response.text
