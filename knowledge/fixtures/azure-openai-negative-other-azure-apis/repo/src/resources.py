import requests

ARM = "https://management.azure.com"


def list_groups(sub: str, token: str):
    url = f"{ARM}/subscriptions/{sub}/resourcegroups?api-version=2021-04-01"
    return requests.get(url, headers={"Authorization": f"Bearer {token}"}).json()


def list_containers(account: str, token: str):
    url = f"https://{account}.blob.core.windows.net/?comp=list&api-version=2023-11-03"
    return requests.get(url, headers={"Authorization": f"Bearer {token}", "x-ms-version": "2023-11-03"}).text
