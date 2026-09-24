require "anthropic"
require "octokit"

# Finds call sites of a deprecated helper across the organization and asks
# Claude to draft a migration note for each repository.
class ReviewBot
  def initialize(org:)
    @org = org
    @github = Octokit::Client.new(access_token: ENV.fetch("GITHUB_TOKEN"))
    @claude = Anthropic::Client.new(api_key: ENV.fetch("ANTHROPIC_API_KEY"))
  end

  def run(symbol)
    results = @github.search_code("#{symbol} org:#{@org}")
    results.items.group_by { |item| item.repository.full_name }.map do |repo, items|
      [repo, draft_note(symbol, items.map(&:path))]
    end
  end

  private

  def draft_note(symbol, paths)
    message = @claude.messages.create(
      model: "claude-sonnet-4-5",
      max_tokens: 1024,
      temperature: 0.2,
      messages: [{ role: "user", content: "Write a migration note for #{symbol} used in: #{paths.join(', ')}" }]
    )
    message.content.first.text
  end
end
