import discord

intents = discord.Intents.default()
client = discord.Client(intents=intents)


async def provision_team_server(team_name: str) -> int:
    guild = await client.create_guild(name=f"{team_name} workspace")
    return guild.id
