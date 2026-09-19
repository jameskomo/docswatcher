const sgMail = require("@sendgrid/mail");

sgMail.setApiKey(process.env.SENDGRID_KEY);

async function sendReceipt(to, orderId) {
  return sgMail.send({
    to,
    from: "receipts@example.com",
    subject: `Receipt for ${orderId}`,
    text: "Thanks for your order.",
  });
}

module.exports = { sendReceipt };
