from facebook_business.adobjects.adaccount import AdAccount
from facebook_business.api import FacebookAdsApi

FacebookAdsApi.init(app_id="1234567890", app_secret="secret", access_token="token", api_version="v23.0")


def spend(account_id: str):
    return AdAccount(f"act_{account_id}").get_insights(fields=["spend", "impressions"])
