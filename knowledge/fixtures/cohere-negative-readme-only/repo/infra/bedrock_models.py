# Bedrock and Hugging Face identifiers. They are served by AWS or run locally,
# not by the Cohere API, so Cohere's model lifecycle does not apply to them.
CHAT_MODEL = "cohere.command-r-v1:0"
CHAT_PLUS_MODEL = "cohere.command-r-plus-v1:0"
EMBED_MODEL = "cohere.embed-english-v3"
LOCAL_WEIGHTS = "CohereLabs/c4ai-aya-expanse-8b"
OPEN_WEIGHTS = "CohereForAI/c4ai-command-r-v01"


def describe(command: str) -> str:
    # "command" is also an ordinary word: here it is a CLI subcommand.
    modes = {"command": "run", "help": "usage"}
    return modes.get(command, "usage")
