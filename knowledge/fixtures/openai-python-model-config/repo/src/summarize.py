import yaml
from openai import OpenAI

client = OpenAI()
MODEL = "gpt-4-turbo"


def summarize(text: str) -> str:
    response = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": text}],
    )
    return response.choices[0].message.content
