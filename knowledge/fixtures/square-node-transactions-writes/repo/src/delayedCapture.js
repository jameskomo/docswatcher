const { client } = require("./client");

// Orders are charged with delay_capture and settled once the kitchen confirms.
async function settle(locationId, transactionId) {
  const { result } = await client.transactionsApi.captureTransaction(locationId, transactionId);
  return result;
}

async function release(locationId, transactionId) {
  const { result } = await client.transactionsApi.voidTransaction(locationId, transactionId);
  return result;
}

module.exports = { settle, release };
