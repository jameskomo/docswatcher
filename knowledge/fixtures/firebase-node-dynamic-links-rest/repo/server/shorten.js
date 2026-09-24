const API = "https://firebasedynamiclinks.googleapis.com/v1/shortLinks";

async function shorten(longLink, apiKey) {
  const res = await fetch(`${API}?key=${apiKey}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ longDynamicLink: longLink, suffix: { option: "SHORT" } }),
  });
  return (await res.json()).shortLink;
}

async function clicks(shortLink, apiKey) {
  const url = `https://firebasedynamiclinks.googleapis.com/v1/${encodeURIComponent(shortLink)}/linkStats?durationDays=7&key=${apiKey}`;
  return (await fetch(url)).json();
}

module.exports = { shorten, clicks };
