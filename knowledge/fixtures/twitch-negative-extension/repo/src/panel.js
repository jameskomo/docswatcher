// Extension PubSub goes through the helper, which Twitch did not shut down.
window.Twitch.ext.listen("broadcast", (target, contentType, message) => {
  render(JSON.parse(message));
});

export const followers = (id, token) => fetch(`https://api.twitch.tv/helix/channels/followers?broadcaster_id=${id}`, {
  headers: { Authorization: `Bearer ${token}`, "Client-Id": window.Twitch.ext.clientId },
});
