import { call } from "./paypal-call.js";

export const createInvoice = (token, invoice) => call("POST", "/v1/invoicing/invoices", token, invoice);
export const searchInvoices = (token, query) => call("POST", "/v1/invoicing/search", token, query);
export const listTemplates = (token) => call("GET", "/v1/invoicing/templates", token);
