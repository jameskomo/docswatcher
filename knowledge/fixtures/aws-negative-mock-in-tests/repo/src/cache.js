const fs = require("node:fs/promises");
const path = require("node:path");

async function put(dir, key, body) {
  await fs.writeFile(path.join(dir, key), body);
}

module.exports = { put };
