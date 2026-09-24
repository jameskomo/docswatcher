# Forum service

Threads and comments for the community forum. The moderation model borrows
from YouTube: a comment can be marked as spam (`comments.markAsSpam()` in their
API) and a thread's title can be edited. Related threads are computed locally,
unlike YouTube's retired `relatedToVideoId` search.
