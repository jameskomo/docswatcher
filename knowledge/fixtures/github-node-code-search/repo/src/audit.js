const { Octokit } = require("@octokit/rest");

const octokit = new Octokit({ auth: process.env.GITHUB_TOKEN });

// Runs nightly across every repository in the org.
async function findUsages(org, symbol) {
  const { data } = await octokit.rest.search.code({
    q: `${symbol}+org:${org}`,
    per_page: 100,
  });
  return data.items.map((item) => `${item.repository.full_name}/${item.path}`);
}

const SEARCH_ENDPOINT = "https://api.github.com/search/code";

module.exports = { findUsages, SEARCH_ENDPOINT };
