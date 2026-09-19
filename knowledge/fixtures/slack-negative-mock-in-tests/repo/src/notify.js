const nodemailer = require("nodemailer");

async function notify(to, subject, body) {
  const transport = nodemailer.createTransport({ sendmail: true });
  return transport.sendMail({ to, subject, text: body });
}

module.exports = { notify };
