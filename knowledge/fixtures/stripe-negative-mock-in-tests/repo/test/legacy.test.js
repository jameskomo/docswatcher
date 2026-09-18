// Legacy mock kept for a migration test. Production code no longer calls sources.
test("legacy source mock still parses", async () => {
  const stripe = { sources: { create: async () => ({ id: "src_123" }) } };
  const src = await stripe.sources.create({ type: "card" });
  expect(src.id).toBe("src_123");
});
