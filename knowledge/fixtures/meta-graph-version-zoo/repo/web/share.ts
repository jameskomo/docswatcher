export const GRAPH_VIDEO = "https://graph-video.facebook.com/v25.0";

export async function uploadVideo(pageId: string, token: string, file: Blob) {
  const form = new FormData();
  form.append("source", file);
  return fetch(`${GRAPH_VIDEO}/${pageId}/videos?access_token=${token}`, { method: "POST", body: form });
}
