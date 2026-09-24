# Pin archiver

Copies pinned messages from a Discord channel into the wiki every night.

The first version used `https://discord.com/api/v6/channels/{id}/pins`; it now
uses the paginated `/messages/pins` route on API v10.
