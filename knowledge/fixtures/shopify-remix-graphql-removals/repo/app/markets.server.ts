import type { AdminApiContext } from "@shopify/shopify-app-remix/server";

const UPDATE_MARKET_CURRENCY = `#graphql
  mutation UpdateMarketCurrency($marketId: ID!, $input: MarketCurrencySettingsUpdateInput!) {
    marketCurrencySettingsUpdate(marketId: $marketId, input: $input) {
      market {
        id
      }
      userErrors {
        field
        message
      }
    }
  }`;

export async function setBaseCurrency(admin: AdminApiContext, marketId: string, currency: string) {
  const response = await admin.graphql(UPDATE_MARKET_CURRENCY, {
    variables: { marketId, input: { baseCurrency: { currencyCode: currency } } },
  });
  return response.json();
}
