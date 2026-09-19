# Billing worker

Invoices are rendered here and handed to the mail service. SendGrid credentials
live in that service, not this one. The old v2 path
https://api.sendgrid.com/api/mail.send.json is documented here only for history.
