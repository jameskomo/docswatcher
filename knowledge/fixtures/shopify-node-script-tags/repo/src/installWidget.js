// Loads the reviews widget on the merchant's storefront after install.
const WIDGET_SRC = "https://cdn.reviews-widget.example/widget.js";

const CREATE_SCRIPT_TAG = `
  mutation ScriptTagCreate($input: ScriptTagInput!) {
    scriptTagCreate(input: $input) {
      scriptTag { id src displayScope }
      userErrors { field message }
    }
  }
`;

const UPDATE_SCRIPT_TAG = `
  mutation ScriptTagUpdate($id: ID!, $input: ScriptTagInput!) {
    scriptTagUpdate(id: $id, input: $input) {
      scriptTag { id src }
      userErrors { field message }
    }
  }
`;

export async function installWidget(client) {
  const { data } = await client.request(CREATE_SCRIPT_TAG, {
    variables: { input: { src: WIDGET_SRC, displayScope: "ONLINE_STORE", cache: true } },
  });
  return data.scriptTagCreate.scriptTag;
}

export async function moveWidget(client, id, src) {
  const { data } = await client.request(UPDATE_SCRIPT_TAG, { variables: { id, input: { src } } });
  return data.scriptTagUpdate.scriptTag;
}
