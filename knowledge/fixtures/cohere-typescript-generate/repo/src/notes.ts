import { CohereClient } from "cohere-ai";

const cohere = new CohereClient({ token: process.env.COHERE_API_KEY! });

export async function releaseNotes(commits: string[]): Promise<string> {
  const prompt = `Turn these commit messages into release notes:\n${commits.join("\n")}`;
  const response = await cohere.generate({ prompt, maxTokens: 400 });
  return response.generations[0].text;
}

export async function tldr(text: string): Promise<string> {
  const response = await cohere.summarize({ text: text, length: "short" });
  return response.summary ?? "";
}

export async function label(inputs: string[]) {
  const response = await cohere.classify({
    inputs,
    examples: [
      { text: "refund please", label: "billing" },
      { text: "app crashes on start", label: "bug" },
    ],
  });
  return response.classifications.map((c) => c.prediction);
}
