import path from "node:path";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import type { Db } from "./client";

export async function runMigrations(db: Db, migrationsFolder = path.resolve(process.cwd(), "drizzle")) {
  await migrate(db, { migrationsFolder });
}
