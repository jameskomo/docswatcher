import type { LoaderFunctionArgs } from "@remix-run/node";
import { json } from "@remix-run/node";
import { useLoaderData } from "@remix-run/react";
import { authenticate } from "../shopify.server";

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const { admin } = await authenticate.admin(request);
  const response = await admin.graphql(
    `#graphql
    query ActiveAutomaticDiscounts {
      automaticDiscounts(first: 25, query: "status:active") {
        nodes {
          ... on DiscountAutomaticBxgy {
            title
            status
          }
        }
      }
    }`,
  );
  const { data } = await response.json();
  return json({ discounts: data.automaticDiscounts.nodes });
};

export default function Discounts() {
  const { discounts } = useLoaderData<typeof loader>();
  return (
    <ul>
      {discounts.map((d: { title: string }) => (
        <li key={d.title}>{d.title}</li>
      ))}
    </ul>
  );
}
