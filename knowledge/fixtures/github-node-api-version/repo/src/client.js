const { Octokit } = require("@octokit/rest");

const octokit = new Octokit({
  auth: process.env.GITHUB_TOKEN,
  headers: {
    "X-GitHub-Api-Version": "2022-11-28",
  },
});

async function latestRelease(owner, repo) {
  const { data } = await octokit.rest.repos.getLatestRelease({ owner, repo });
  return data.tag_name;
}

module.exports = { octokit, latestRelease };
