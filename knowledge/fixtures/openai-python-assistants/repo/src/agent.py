import httpx
from openai import OpenAI

client = OpenAI()

THREADS_URL = "https://api.openai.com/v1/threads"


def build_assistant():
    return client.beta.assistants.create(
        name="Support agent",
        model="gpt-4-turbo",
        instructions="Answer support questions from the handbook.",
    )


def list_threads(token: str):
    return httpx.get(THREADS_URL, headers={"Authorization": f"Bearer {token}"}).json()
