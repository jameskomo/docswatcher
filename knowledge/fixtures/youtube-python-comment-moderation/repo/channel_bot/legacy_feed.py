import xml.etree.ElementTree as ET
from urllib.request import urlopen

UPLOADS_FEED = "http://gdata.youtube.com/feeds/api/users/{user}/uploads?v=2"
STATS_URL = "https://www.googleapis.com/youtube/v3/videos"


def latest_uploads(user: str):
    with urlopen(UPLOADS_FEED.format(user=user)) as resp:
        root = ET.parse(resp).getroot()
    return [entry.findtext("{http://www.w3.org/2005/Atom}title") for entry in root]
