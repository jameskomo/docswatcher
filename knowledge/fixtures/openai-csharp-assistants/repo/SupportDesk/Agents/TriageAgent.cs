using System.ClientModel;
using Anthropic;
using Anthropic.Models.Messages;
using OpenAI;
using OpenAI.Assistants;
using OpenAI.Chat;

namespace SupportDesk.Agents;

#pragma warning disable OPENAI001 // Assistants are an evaluation API in the OpenAI package.

public class TriageAgent
{
    private readonly OpenAIClient _openai;
    private readonly AnthropicClient _claude;

    public TriageAgent(OpenAIClient openai, AnthropicClient claude)
    {
        _openai = openai;
        _claude = claude;
    }

    public async Task<string> ProvisionAsync()
    {
        AssistantClient assistants = _openai.GetAssistantClient();
        Assistant assistant = await assistants.CreateAssistantAsync("gpt-4-turbo", new AssistantCreationOptions
        {
            Name = "Support triage",
            Instructions = "Route each ticket to the right queue.",
        });
        return assistant.Id;
    }

    public async Task<string> SummarizeAsync(string ticket)
    {
        ChatClient chat = _openai.GetChatClient("gpt-4o-mini");
        ChatCompletion completion = await chat.CompleteChatAsync(new UserChatMessage($"Summarize: {ticket}"));
        return completion.Content[0].Text;
    }

    public async Task<string> SecondOpinionAsync(string ticket)
    {
        var message = await _claude.Messages.Create(new MessageCreateParams
        {
            Model = "claude-sonnet-4-5",
            MaxTokens = 512,
            Temperature = 0.3,
            Messages = [new() { Role = Role.User, Content = ticket }],
        });
        return message.Content[0].ToString();
    }
}
