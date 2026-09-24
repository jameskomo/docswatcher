import os

from simple_salesforce import Salesforce, SalesforceLogin


def password_client() -> Salesforce:
    return Salesforce(
        username=os.environ["SF_USERNAME"],
        password=os.environ["SF_PASSWORD"],
        security_token=os.environ["SF_TOKEN"],
    )


def session_for_bulk():
    session_id, instance = SalesforceLogin(
        username=os.environ["SF_USERNAME"],
        password=os.environ["SF_PASSWORD"],
        security_token=os.environ["SF_TOKEN"],
    )
    return session_id, instance


def jwt_client() -> Salesforce:
    # OAuth 2.0 JWT bearer flow: not a SOAP login.
    return Salesforce(
        username=os.environ["SF_USERNAME"],
        consumer_key=os.environ["SF_CONSUMER_KEY"],
        privatekey_file="secrets/server.key",
    )
