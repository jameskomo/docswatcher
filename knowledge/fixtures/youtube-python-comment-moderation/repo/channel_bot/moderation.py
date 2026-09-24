from googleapiclient.discovery import build


def client(credentials):
    return build("youtube", "v3", credentials=credentials)


def report_spam(youtube, comment_id: str) -> None:
    youtube.comments().markAsSpam(id=comment_id).execute()


def edit_pinned_thread(youtube, thread_id: str, text: str) -> dict:
    body = {"id": thread_id, "snippet": {"topLevelComment": {"snippet": {"textOriginal": text}}}}
    return youtube.commentThreads().update(part="snippet", body=body).execute()


def related_videos(youtube, video_id: str, limit: int = 10) -> list:
    response = youtube.search().list(
        part="snippet",
        type="video",
        relatedToVideoId=video_id,
        maxResults=limit,
    ).execute()
    return response["items"]
