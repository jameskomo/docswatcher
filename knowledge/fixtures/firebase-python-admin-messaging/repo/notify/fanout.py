import firebase_admin
from firebase_admin import messaging

firebase_admin.initialize_app()


def fan_out(tokens, title, body):
    message = messaging.MulticastMessage(
        tokens=tokens,
        notification=messaging.Notification(title=title, body=body),
    )
    return messaging.send_multicast(message)


def flush(outbox):
    messages = [messaging.Message(token=t, data={"body": b}) for t, b in outbox]
    return messaging.send_all(messages)


def one(token, body):
    return messaging.send(messaging.Message(token=token, data={"body": body}))
