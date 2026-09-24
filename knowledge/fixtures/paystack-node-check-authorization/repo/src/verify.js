const axios = require("axios");

const paystackApi = axios.create({
  headers: { Authorization: `Bearer ${process.env.PAYSTACK_SECRET_KEY}` },
});

async function verifyPayment(reference) {
  const { data } = await paystackApi.get(`https://api.paystack.co/transaction/verify/${reference}`);
  return data.data.status === "success";
}

module.exports = { verifyPayment };
