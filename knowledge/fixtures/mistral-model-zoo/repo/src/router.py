import yaml
from mistralai import Mistral

client = Mistral()

with open("config/models.yaml") as f:
    MODELS = yaml.safe_load(f)


def complete(workload: str, prompt: str) -> str:
    response = client.chat.complete(
        model=MODELS.get(workload, MODELS["default"]),
        messages=[{"role": "user", "content": prompt}],
    )
    return response.choices[0].message.content
