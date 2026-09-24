import requests

# The nightly export predates the SDK and still builds URLs by hand.
CAMPAIGNS = "https://graph.facebook.com/v21.0/act_{account}/campaigns"
ADSETS = "https://graph.facebook.com/v22.0/act_{account}/adsets"
INSIGHTS = "https://graph.facebook.com/v24.0/act_{account}/insights"


def export(account: str, token: str):
    return [requests.get(u.format(account=account), params={"access_token": token}).json()
            for u in (CAMPAIGNS, ADSETS, INSIGHTS)]
