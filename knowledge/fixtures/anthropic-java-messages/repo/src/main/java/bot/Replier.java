package bot;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

public class Replier {

  private static final String MODEL = "claude-3-5-sonnet-20241022";

  private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

  public String reply(String question) {
    MessageCreateParams params =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(1024)
            .temperature(0.2)
            .addUserMessage(question)
            .build();
    Message message = client.messages().create(params);
    return message.content().getFirst().text().orElseThrow().text();
  }
}
