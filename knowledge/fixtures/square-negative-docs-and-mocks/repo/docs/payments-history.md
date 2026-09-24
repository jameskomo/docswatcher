# Payments history

Until 2020 the register charged cards through Square's Transactions API:
`POST https://connect.squareup.com/v2/locations/{location_id}/transactions`, with
refunds sent to
`https://connect.squareup.com/v2/locations/{location_id}/transactions/{transaction_id}/refund`.

Seller tokens were kept alive with `oAuthApi.renewToken` against
`https://connect.squareup.com/oauth2/clients/{client_id}/access-token/renew`.

Both were replaced when checkout moved to the payments service, which owns the
Square integration now. This repository no longer talks to Square.
