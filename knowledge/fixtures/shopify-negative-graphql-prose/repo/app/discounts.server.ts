// Formerly read through the automaticDiscounts query; see docs/shopify-migration.md.
const AUTOMATIC_DISCOUNTS = `#graphql
  query AutomaticDiscounts {
    discountNodes(first: 25, query: "method:automatic") {
      nodes { id discount { ... on DiscountAutomaticBxgy { title } } }
    }
  }`;

type AutomaticDiscounts = { title: string }[];

export async function listAutomaticDiscounts(admin: { graphql: (q: string) => Promise<Response> }) {
  const response = await admin.graphql(AUTOMATIC_DISCOUNTS);
  const { data } = await response.json();
  const automaticDiscounts: AutomaticDiscounts = data.discountNodes.nodes.map((n: any) => n.discount);
  return { automaticDiscounts, includeRestOfWorld: false };
}
