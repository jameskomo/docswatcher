// Shopify's own app template pins the API version in this import path.
import { restResources } from "@shopify/shopify-api/rest/admin/2024-10";
import { shopifyApp } from "@shopify/shopify-app-express";

export const shopify = shopifyApp({ api: { restResources } });
