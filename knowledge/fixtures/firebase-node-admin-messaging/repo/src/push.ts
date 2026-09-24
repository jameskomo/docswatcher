import { initializeApp } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";

initializeApp();
const messaging = getMessaging();

export async function notifyDevices(tokens: string[], title: string, body: string) {
  const res = await messaging.sendMulticast({ tokens, notification: { title, body } });
  return res.failureCount;
}

export async function flush(queue: { token: string; body: string }[]) {
  return messaging.sendAll(queue.map((q) => ({ token: q.token, notification: { body: q.body } })));
}

export async function breakingNews(headline: string) {
  await messaging.sendToTopic("news", { notification: { title: "Breaking", body: headline } });
  await messaging.sendToCondition("'news' in topics && 'ke' in topics", { data: { headline } });
}

export async function legacyDirect(token: string, group: string) {
  await messaging.sendToDevice(token, { data: { ping: "1" } }, { priority: "high" });
  await messaging.sendToDeviceGroup(group, { data: { ping: "1" } });
}

export async function modern(token: string) {
  return messaging.send({ token, notification: { title: "Hi" } });
}
