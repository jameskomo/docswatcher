const PAGES = { general: "https://paystack.com/pay/donate-general" };

function donationLink(fund) {
  return PAGES[fund] || PAGES.general;
}

module.exports = { donationLink };
