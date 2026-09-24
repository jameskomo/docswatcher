import { call } from "./paypal-call.js";

export const referMerchant = (token, referral) => call("POST", "/v1/customer/partner-referrals", token, referral);
