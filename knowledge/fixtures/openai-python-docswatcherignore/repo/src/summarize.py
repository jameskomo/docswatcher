from openai import OpenAI

client = OpenAI()
reply = client.chat.completions.create(model="gpt-4-turbo", messages=[{"role": "user", "content": "hi"}])
