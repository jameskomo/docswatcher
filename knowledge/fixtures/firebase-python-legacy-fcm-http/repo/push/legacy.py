import os

import requests

FCM_URL = "https://fcm.googleapis.com/fcm/send"
FCM_BATCH_URL = "https://fcm.googleapis.com/batch"
SERVER_KEY = os.environ["FCM_SERVER_KEY"]


def send(registration_ids, title, body):
    payload = {"registration_ids": registration_ids, "notification": {"title": title, "body": body}}
    headers = {"Authorization": f"key={SERVER_KEY}", "Content-Type": "application/json"}
    return requests.post(FCM_URL, json=payload, headers=headers, timeout=10).json()
