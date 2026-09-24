const { Client, Environment } = require("square");

const client = new Client({
  accessToken: process.env.SQUARE_ACCESS_TOKEN,
  environment: Environment.Production,
  squareVersion: "2019-08-14",
});

module.exports = { client };
