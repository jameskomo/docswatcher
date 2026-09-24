package com.contoso;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.credential.AzureKeyCredential;
import java.util.List;

public class SupportBot {
  private final OpenAIClient client = new OpenAIClientBuilder()
      .endpoint(System.getenv("AZURE_OPENAI_ENDPOINT"))
      .credential(new AzureKeyCredential(System.getenv("AZURE_OPENAI_API_KEY")))
      .buildClient();

  public String answer(String question) {
    var options = new ChatCompletionsOptions(List.of(new ChatRequestUserMessage(question)));
    return client.getChatCompletions("support", options).getChoices().get(0).getMessage().getContent();
  }
}
