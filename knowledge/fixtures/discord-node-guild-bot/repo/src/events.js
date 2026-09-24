const { Client, GatewayIntentBits, ChannelType } = require("discord.js");

const client = new Client({ intents: [GatewayIntentBits.Guilds] });

// Each hackathon gets its own server, created and owned by the bot.
async function createEventGuild(eventName) {
  const guild = await client.guilds.create({
    name: `${eventName} hackathon`,
    channels: [{ name: "announcements", type: ChannelType.GuildText }],
  });
  return guild.id;
}

module.exports = { client, createEventGuild };
