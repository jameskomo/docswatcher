<h1>Assistants migration</h1>
<p>Replace <code>$client->assistants()->create([...])</code> with the Responses API.</p>
<?php

/**
 * Renders the migration guide. The old call was OpenAI::assistants()->create()
 * and the chat call was $client->chat()->create().
 */
function migration_steps(): array
{
    // $client->assistants()->create(['model' => $model]);
    # $client->chat()->create(['model' => $model]);
    return [
        'before' => '$client->assistants()->create([...])',
        'after' => "\$client->responses()->create([...])",
        'note' => <<<'TXT'
            $client->chat()->create() keeps working.
            TXT,
    ];
}
?>
<footer>Generated from $client->assistants()->create examples.</footer>
