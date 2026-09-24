import express from "express";
import { quote } from "./quotes";

// An application object with its own directions() method; it takes no params
// object, so it is not the Maps client.
const planner = { directions: (from: string, to: string) => quote(from, to) };

export const router = express.Router();
router.get("/quote", async (req, res) => {
  res.json(await planner.directions(String(req.query.from), String(req.query.to)));
});
