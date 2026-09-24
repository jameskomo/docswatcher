<?php

declare(strict_types=1);

namespace Acme\Gateway;

use Anthropic\Client as AnthropicClient;
use OpenAI\Client as OpenAIClient;

/** Routes a prompt to whichever provider the tenant is configured for. */
final class Gateway
{
    public function __construct(
        private readonly OpenAIClient $openai,
        private readonly AnthropicClient $anthropic,
    ) {
    }

    public function complete(string $provider, string $prompt): string
    {
        if ($provider === 'anthropic') {
            $message = $this->anthropic->messages->create(
                maxTokens: 1024,
                messages: [['role' => 'user', 'content' => $prompt]],
                model: 'claude-sonnet-4-5',
                topP: 0.9,
            );
            return $message->content[0]->text;
        }

        $result = $this->openai->chat()->create([
            'model' => 'gpt-4o',
            'messages' => [['role' => 'user', 'content' => $prompt]],
        ]);
        return $result->choices[0]->message->content;
    }

    public function createAgent(string $name): string
    {
        return $this->openai->assistants()->create([
            'model' => 'gpt-4o',
            'name' => $name,
        ])->id;
    }
}
