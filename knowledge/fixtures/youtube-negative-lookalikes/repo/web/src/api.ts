// Client for our own forum API. Names mirror the backend resources.
export interface SearchParams {
  q: string;
  relatedToVideoId?: string;
}

export const api = {
  comments: {
    markSpam: (id: string) => fetch(`/api/comments/${id}/spam`, { method: "POST" }),
  },
  threads: {
    update: (id: string, title: string) =>
      fetch(`/api/threads/${id}`, { method: "PATCH", body: JSON.stringify({ title }) }),
  },
};

export function renameThread(id: string, title: string) {
  return api.threads.update(id, title);
}
