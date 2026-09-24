import os

import paystack

paystack.api_key = os.environ["PAYSTACK_SECRET_KEY"]


def can_renew(email: str, amount_kobo: int, authorization_code: str) -> bool:
    """Pre-check a saved card before the monthly renewal charge."""
    response = paystack.Transaction.check_authorization(
        email=email,
        amount=amount_kobo,
        authorization_code=authorization_code,
    )
    return bool(response.status)
