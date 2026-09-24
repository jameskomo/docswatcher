import os

import requests

ENDPOINT = "https://contoso.openai.azure.com/openai/deployments/summarizer/chat/completions?api-version=2024-10-21"


def raw_completion(prompt: str) -> str:
    r = requests.post(ENDPOINT, headers={"api-key": os.environ["AZURE_OPENAI_API_KEY"]},
                      json={"messages": [{"role": "user", "content": prompt}]})
    return r.json()["choices"][0]["message"]["content"]
