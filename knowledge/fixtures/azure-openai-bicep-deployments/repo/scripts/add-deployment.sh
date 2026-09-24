#!/usr/bin/env bash
# One-off deployment used by the batch evaluator.
set -euo pipefail
az cognitiveservices account deployment create \
  --name contoso-openai --resource-group rg-ai \
  --deployment-name batch-eval \
  --model-name gpt-4o --model-version "2024-11-20" \
  --model-format OpenAI --sku-name "Standard" --sku-capacity 10
