const mailchimp = require("@mailchimp/mailchimp_marketing");
const transactional = require("@mailchimp/mailchimp_transactional")(process.env.MANDRILL_KEY);

mailchimp.setConfig({ apiKey: process.env.MAILCHIMP_API_KEY, server: process.env.MAILCHIMP_DC });

// Transactional is served from its own host and has always been version 1.0.
const MANDRILL_SEND = "https://mandrillapp.com/api/1.0/messages/send.json";

async function addToList(listId, email) {
  return mailchimp.lists.addListMember(listId, { email_address: email, status: "pending" });
}

async function sendReceipt(to, html) {
  return transactional.messages.send({ message: { to: [{ email: to }], html, subject: "Your receipt" } });
}

module.exports = { addToList, sendReceipt, MANDRILL_SEND };
