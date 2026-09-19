# Embedding model identifiers are isolated in their own fixture: they share a
# naming shape with other vendors' embedding models, so keeping them apart
# makes any cross-provider overlap obvious in the expected inventory.
LEGACY_EMBEDDING = "embedding-001"
CURRENT_EMBEDDING = "text-embedding-004"
