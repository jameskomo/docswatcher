const headers = () => ({
  "Client-Id": process.env.TWITCH_CLIENT_ID,
  Authorization: `Bearer ${process.env.TWITCH_TOKEN}`,
});

export const followers = (id) => fetch(`https://api.twitch.tv/helix/users/follows?to_id=${id}`, { headers: headers() });
export const streamTags = (id) => fetch(`https://api.twitch.tv/helix/streams/tags?broadcaster_id=${id}`, { headers: headers() });
export const allTags = () => fetch("https://api.twitch.tv/helix/tags/streams", { headers: headers() });
export const setTags = (id, tagIds) => fetch(`https://api.twitch.tv/helix/streams/tags?broadcaster_id=${id}`, {
  method: "PUT", headers: headers(), body: JSON.stringify({ tag_ids: tagIds }),
});
export const nowPlaying = (id) => fetch(`https://api.twitch.tv/helix/soundtrack/current_track?broadcaster_id=${id}`, { headers: headers() });
export const playlist = (id) => fetch(`https://api.twitch.tv/helix/soundtrack/playlist?id=${id}`, { headers: headers() });
export const playlists = () => fetch("https://api.twitch.tv/helix/soundtrack/playlists", { headers: headers() });
export const hypeTrains = (id) => fetch(`https://api.twitch.tv/helix/hypetrain/events?broadcaster_id=${id}`, { headers: headers() });
export const banEvents = (id) => fetch(`https://api.twitch.tv/helix/moderation/banned/events?broadcaster_id=${id}`, { headers: headers() });
export const modEvents = (id) => fetch(`https://api.twitch.tv/helix/moderation/moderators/events?broadcaster_id=${id}`, { headers: headers() });
export const subEvents = (id) => fetch(`https://api.twitch.tv/helix/subscriptions/events?broadcaster_id=${id}`, { headers: headers() });
