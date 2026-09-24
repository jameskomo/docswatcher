// Other APIs also put a vNN.0 version in the path. None of these are Salesforce.
const GRAPH = "https://graph.facebook.com/v30.0/me/accounts";
const PACKAGE_VERSION = "30.0";

async function pages(token) {
  return (await fetch(`${GRAPH}?access_token=${token}`)).json();
}

module.exports = { pages, PACKAGE_VERSION };
