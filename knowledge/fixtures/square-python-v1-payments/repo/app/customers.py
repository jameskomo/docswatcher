import os

from square.client import Client

client = Client(
    access_token=os.environ["SQUARE_ACCESS_TOKEN"],
    environment="production",
    square_version="2021-12-15",
)


def find_customer(email: str):
    result = client.customers.search_customers(
        body={"query": {"filter": {"email_address": {"exact": email}}}}
    )
    return result.body.get("customers", [])
