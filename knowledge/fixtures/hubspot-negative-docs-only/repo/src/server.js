const express = require("express");

const app = express();

// Our own API, which happens to mirror HubSpot's old path layout.
app.get("/contacts/v1/lists", (req, res) => res.json({ lists: [] }));
app.get("/owners/v2/owners", (req, res) => res.json([]));

module.exports = app;
