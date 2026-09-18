import Anthropic from "@anthropic-ai/sdk";

const client = new Anthropic();

export async function reply(question: string): Promise<string> {
  const msg = await client.messages.create({
    model: "claude-3-haiku-20240307",
    max_tokens: 1024,
    messages: [{ role: "user", content: question }],
  });
  return msg.content[0].type === "text" ? msg.content[0].text : "";
}
