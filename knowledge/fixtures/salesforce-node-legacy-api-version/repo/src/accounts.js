const API_BASE = "/services/data/v29.0";

async function recentAccounts(instanceUrl, accessToken) {
  const soql = encodeURIComponent("SELECT Id, Name FROM Account ORDER BY CreatedDate DESC LIMIT 50");
  const res = await fetch(`${instanceUrl}${API_BASE}/query?q=${soql}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  return (await res.json()).records;
}

module.exports = { recentAccounts };
