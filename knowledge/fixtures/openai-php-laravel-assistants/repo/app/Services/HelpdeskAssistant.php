<?php

namespace App\Services;

use App\Models\Ticket;
use OpenAI\Laravel\Facades\OpenAI;

class HelpdeskAssistant
{
    public function provision(): string
    {
        $assistant = OpenAI::assistants()->create([
            'model' => 'gpt-4-turbo',
            'name' => 'Helpdesk',
            'instructions' => 'Answer questions using the attached knowledge base.',
            'tools' => [['type' => 'file_search']],
        ]);

        return $assistant->id;
    }

    public function suggestReply(Ticket $ticket): string
    {
        $response = \OpenAI\Laravel\Facades\OpenAI::chat()->create([
            'model' => 'gpt-4o-mini',
            'messages' => [
                ['role' => 'user', 'content' => "Draft a reply to: {$ticket->body}"],
            ],
        ]);

        return $response->choices[0]->message->content;
    }
}
