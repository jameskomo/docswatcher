from forum.models import Comment, Thread


def flag(comment: Comment) -> None:
    comment.mark_as_spam()
    comment.save()


def rename(thread: Thread, title: str) -> Thread:
    thread.title = title
    thread.save(update_fields=["title"])
    return thread


def related_threads(thread: Thread, limit: int = 5):
    return Thread.objects.filter(tags__in=thread.tags.all()).exclude(id=thread.id)[:limit]
