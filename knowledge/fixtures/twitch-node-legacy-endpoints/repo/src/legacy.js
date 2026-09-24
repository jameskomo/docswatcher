// Written for API v5 and the undocumented hosts; never migrated.
export const channel = (login) => fetch(`https://api.twitch.tv/kraken/channels/${login}`, {
  headers: { Accept: "application/vnd.twitchtv.v5+json", "Client-ID": process.env.TWITCH_CLIENT_ID },
});
export const globalBadges = () => fetch("https://badges.twitch.tv/v1/badges/global/display");
export const chatters = (login) => fetch(`https://tmi.twitch.tv/group/user/${login}/chatters`);
export const subscribe = (topic, callback) => fetch("https://api.twitch.tv/helix/webhooks/hub", {
  method: "POST",
  body: JSON.stringify({ "hub.mode": "subscribe", "hub.topic": topic, "hub.callback": callback, "hub.lease_seconds": 864000 }),
});
export const webhookSubscriptions = () => fetch("https://api.twitch.tv/helix/webhooks/subscriptions");
