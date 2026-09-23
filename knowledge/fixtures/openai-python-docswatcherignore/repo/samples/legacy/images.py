# A sample kept for reference. DocsWatcher must not report it: .docswatcherignore excludes samples/.
from openai import OpenAI

OpenAI().images.generate(model="dall-e-2", prompt="a lighthouse")
