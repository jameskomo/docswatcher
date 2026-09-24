import WebSocket from "ws";

const socket = new WebSocket("wss://pubsub-edge.twitch.tv");

export function listen(topics, token) {
  socket.send(JSON.stringify({ type: "LISTEN", nonce: "alerts", data: { topics, auth_token: token } }));
}
