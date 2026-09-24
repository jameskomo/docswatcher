require "openai"

# Batch worker, moved to the official SDK in 2025.
class TriageWorker
  def initialize
    @openai = OpenAI::Client.new(api_key: ENV["OPENAI_API_KEY"])
  end

  def ensure_assistant
    @openai.beta.assistants.create(model: "gpt-4-turbo", name: "Nightly triage")
  end

  def classify(text)
    completion = @openai.chat.completions.create(
      model: "gpt-4o-mini",
      messages: [{ role: "user", content: "Label this ticket: #{text}" }]
    )
    completion.choices.first.message.content
  end
end
