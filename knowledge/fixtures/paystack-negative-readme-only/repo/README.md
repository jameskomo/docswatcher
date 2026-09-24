# donations-site

Static donation pages. Card payments are taken by the finance team's Paystack
account through a payment page link; this repository never calls the Paystack
API. Older releases pre-checked recurring donors with
`POST https://api.paystack.co/transaction/check_authorization`, which Paystack
has since deprecated.
