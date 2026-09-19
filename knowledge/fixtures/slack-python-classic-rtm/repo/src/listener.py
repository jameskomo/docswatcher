import os

from slack_sdk.rtm_v2 import RTMClient

RTM_URL = "https://slack.com/api/rtm.connect"

rtm = RTMClient(token=os.environ["SLACK_CLASSIC_TOKEN"])


@rtm.on("message")
def handle(client, event):
    if event.get("text", "").startswith("!status"):
        client.web_client.chat_postMessage(channel=event["channel"], text="ok")


def start():
    rtm.start()
