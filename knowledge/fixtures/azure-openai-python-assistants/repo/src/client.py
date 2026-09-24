import os

from openai import AzureOpenAI

client = AzureOpenAI(
    azure_endpoint=os.environ["AZURE_OPENAI_ENDPOINT"],
    api_version="2024-10-21",
)


def summarize(text: str) -> str:
    response = client.chat.completions.create(
        model="summarizer",
        messages=[{"role": "user", "content": text}],
    )
    return response.choices[0].message.content
