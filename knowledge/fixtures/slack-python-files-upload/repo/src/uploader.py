import os

from slack_sdk import WebClient

client = WebClient(token=os.environ["SLACK_BOT_TOKEN"])


def upload_invoice(channel: str, path: str):
    with open(path, "rb") as handle:
        return client.files_upload(
            channels=channel,
            file=handle,
            title="Invoice",
        )
