import { TwitterApi } from "twitter-api-v2";

const client = new TwitterApi({
  appKey: process.env.X_APP_KEY!,
  appSecret: process.env.X_APP_SECRET!,
  accessToken: process.env.X_ACCESS_TOKEN!,
  accessSecret: process.env.X_ACCESS_SECRET!,
});

export async function announceRelease(version: string, bannerPath: string): Promise<string> {
  const mediaId = await client.v1.uploadMedia(bannerPath);
  const { data } = await client.v2.tweet({
    text: `Version ${version} is out.`,
    media: { media_ids: [mediaId] },
  });
  return data.id;
}
