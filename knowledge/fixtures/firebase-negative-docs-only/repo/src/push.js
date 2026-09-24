const { getMessaging } = require("firebase-admin/messaging");

async function notify(tokens, body) {
  return getMessaging().sendEachForMulticast({ tokens, notification: { body } });
}

module.exports = { notify };
