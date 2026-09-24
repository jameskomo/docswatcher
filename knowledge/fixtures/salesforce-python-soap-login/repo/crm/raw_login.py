import requests

LOGIN_URL = "https://login.salesforce.com/services/Soap/u/58.0"

ENVELOPE = """<?xml version="1.0" encoding="utf-8"?>
<env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/" xmlns:urn="urn:partner.soap.sforce.com">
  <env:Body>
    <urn:login><urn:username>{u}</urn:username><urn:password>{p}</urn:password></urn:login>
  </env:Body>
</env:Envelope>"""


def login(username: str, password: str) -> str:
    headers = {"Content-Type": "text/xml; charset=UTF-8", "SOAPAction": "login"}
    res = requests.post(LOGIN_URL, data=ENVELOPE.format(u=username, p=password), headers=headers, timeout=30)
    return res.text
