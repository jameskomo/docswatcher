import cohere
import yaml

with open("config/models.yaml") as fh:
    MODELS = yaml.safe_load(fh)

co = cohere.ClientV2()


def embed(texts):
    return co.embed(texts=texts, model=MODELS["embeddings"]["english"], input_type="search_document")


def rerank(query, docs):
    return co.rerank(query=query, documents=docs, model=MODELS["rerank"]["english"], top_n=5)
