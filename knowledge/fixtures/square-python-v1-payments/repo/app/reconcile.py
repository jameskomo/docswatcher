import os

import requests

V1_PAYMENTS = "https://connect.squareup.com/v1/{location_id}/payments"
HEADERS = {"Authorization": f"Bearer {os.environ['SQUARE_ACCESS_TOKEN']}"}


def daily_payments(location_id: str, begin: str, end: str) -> list:
    """Pull the day's card payments for the ledger export."""
    url = V1_PAYMENTS.format(location_id=location_id)
    response = requests.get(url, headers=HEADERS, params={"begin_time": begin, "end_time": end})
    response.raise_for_status()
    return response.json()


def payment_detail(location_id: str, payment_id: str) -> dict:
    url = f"https://connect.squareup.com/v1/{location_id}/payments/{payment_id}"
    response = requests.get(url, headers=HEADERS)
    response.raise_for_status()
    return response.json()
