def triage(ticket):
    return "billing" if "invoice" in ticket else "other"
