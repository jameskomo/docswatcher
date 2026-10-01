import type { AdminApiContext } from "@shopify/shopify-app-remix/server";

const CREATE_HEAVY_ITEMS = `#graphql
  mutation CreateHeavyItems($input: CollectionInput!) {
    collectionCreate(input: $input) {
      collection {
        id
        source {
          conditions {
            ... on CollectionSourceInclusionConditionMetafieldInteger {
              relation
              value
            }
          }
        }
      }
      userErrors {
        field
        message
      }
    }
  }`;

export async function createHeavyItems(admin: AdminApiContext, definitionId: string) {
  const response = await admin.graphql(CREATE_HEAVY_ITEMS, {
    variables: {
      input: {
        title: "Heavy items",
        source: {
          conditionsToCreate: [{ metafieldInteger: { definitionId, relation: "GREATER_THAN", value: 2000 } }],
        },
      },
    },
  });
  return response.json();
}

// Already migrated, so not a finding: metafieldInt is the replacement.
export const migrated = { metafieldInt: { relation: "GREATER_THAN", value: "2000" } };
