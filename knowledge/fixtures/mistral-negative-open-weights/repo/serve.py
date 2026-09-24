from vllm import LLM, SamplingParams

# Weights from Hugging Face; nothing here calls api.mistral.ai.
llm = LLM(model="mistralai/Mistral-7B-Instruct-v0.3", tokenizer_mode="mistral")


def generate(prompt: str) -> str:
    out = llm.generate([prompt], SamplingParams(max_tokens=256))
    return out[0].outputs[0].text
