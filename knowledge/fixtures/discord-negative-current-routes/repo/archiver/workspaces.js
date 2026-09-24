// Our own multi-tenant workspace service, not Discord: "guilds" are customer teams.
const { WorkspaceClient } = require("./workspace-client");

const client = new WorkspaceClient(process.env.WORKSPACE_URL);

async function onboard(team) {
  return client.guilds.create({ name: team.name, plan: team.plan });
}

module.exports = { onboard };
