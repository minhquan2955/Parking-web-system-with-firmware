import { cpSync, existsSync, mkdirSync } from "node:fs";
if (existsSync(".next/standalone/server.js")) {
  mkdirSync(".next/standalone/.next", { recursive: true });
  cpSync(".next/static", ".next/standalone/.next/static", { recursive: true });
}
