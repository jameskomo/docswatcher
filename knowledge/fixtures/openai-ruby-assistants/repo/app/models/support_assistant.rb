class SupportAssistant < ApplicationRecord
  def self.client
    OpenAI::Client.new(access_token: Rails.application.credentials.openai_api_key)
  end

  def provision!
    response = self.class.client.assistants.create(
      parameters: {
        model: "gpt-4-turbo",
        name: "Support triage",
        instructions: instructions,
        tools: [{ type: "file_search" }]
      }
    )
    update!(openai_assistant_id: response["id"])
  end

  def summarize(ticket)
    self.class.client.chat(
      parameters: {
        model: "gpt-4o-mini",
        messages: [{ role: "user", content: "Summarize: #{ticket.body}" }]
      }
    ).dig("choices", 0, "message", "content")
  end
end
