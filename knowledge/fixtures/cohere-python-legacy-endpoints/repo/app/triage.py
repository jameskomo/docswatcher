from cohere import ClassifyExample

from app.writer import co
from app.reports import builder

EXAMPLES = [
    ClassifyExample(text="I was charged twice this month", label="billing"),
    ClassifyExample(text="The export button does nothing", label="bug"),
    ClassifyExample(text="Can you add dark mode?", label="feature"),
]


def route(tickets: list[str]) -> list[str]:
    result = co.classify(inputs=tickets, examples=EXAMPLES)
    return [c.prediction for c in result.classifications]


def weekly_report(rows):
    # A local report builder that also has a generate method. It takes no
    # prompt argument, so it is not the Cohere call.
    return builder.generate(rows=rows, title="Weekly triage")
