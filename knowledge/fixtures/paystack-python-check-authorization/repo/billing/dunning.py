import os

import requests

CHECK_URL = "https://api.paystack.co/transaction/check_authorization"
HEADERS = {"Authorization": f"Bearer {os.environ['PAYSTACK_SECRET_KEY']}"}


def retry_is_worthwhile(email: str, amount_kobo: int, authorization_code: str) -> bool:
    """The dunning worker runs without the SDK and calls the API directly."""
    response = requests.post(
        CHECK_URL,
        headers=HEADERS,
        json={"email": email, "amount": amount_kobo, "authorization_code": authorization_code},
        timeout=10,
    )
    return response.ok and response.json().get("status") is True
