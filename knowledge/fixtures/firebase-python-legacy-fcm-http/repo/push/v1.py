import google.auth.transport.requests
from google.oauth2 import service_account

PROJECT_ID = "acme-prod"
V1_URL = f"https://fcm.googleapis.com/v1/projects/{PROJECT_ID}/messages:send"


def access_token(path):
    creds = service_account.Credentials.from_service_account_file(
        path, scopes=["https://www.googleapis.com/auth/firebase.messaging"]
    )
    creds.refresh(google.auth.transport.requests.Request())
    return creds.token
