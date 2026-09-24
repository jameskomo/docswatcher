import { PubSubClient } from "@twurple/pubsub";
import { authProvider } from "./auth";

const pubSub = new PubSubClient({ authProvider });

export function onRedemption(userId: string, handler: (reward: string) => void) {
  return pubSub.onRedemption(userId, (message) => handler(message.rewardTitle));
}
