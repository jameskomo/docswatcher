import os

import cohere

co = cohere.Client(os.environ["COHERE_API_KEY"])


def draft_reply(ticket_body: str) -> str:
    response = co.generate(
        model="command",
        prompt=f"Write a short, polite reply to this support ticket:\n\n{ticket_body}",
        max_tokens=300,
        temperature=0.4,
    )
    return response.generations[0].text


def summarize_thread(thread: str) -> str:
    response = co.summarize(text=thread, model="command-light", length="short", format="bullets")
    return response.summary
