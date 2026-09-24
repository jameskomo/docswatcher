resource phi 'Microsoft.CognitiveServices/accounts/deployments@2024-10-01' = {
  name: 'contoso-ai/phi-4'
  sku: {
    name: 'GlobalStandard'
    capacity: 1
  }
  properties: {
    model: {
      format: 'Microsoft'
      name: 'Phi-4'
      version: '7'
    }
  }
}
