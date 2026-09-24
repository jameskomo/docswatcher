import { call } from "./paypal-call.js";

export const createPlan = (token, plan) => call("POST", "/v1/payments/billing-plans", token, plan);
export const createAgreement = (token, agreement) => call("POST", "/v1/payments/billing-agreements", token, agreement);
