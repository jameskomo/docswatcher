# Shopify 2027-01 migration

We moved off the automaticDiscounts query in March. The old call looked like
this and is kept here for reference only:

```graphql
query {
  automaticDiscounts(first: 10) {
    nodes { ... on DiscountAutomaticBxgy { title } }
  }
}
```

marketCurrencySettingsUpdate is gone too; we use marketUpdate. We never set
includeRestOfWorld: true, and scriptTagCreate was replaced by an app embed.
