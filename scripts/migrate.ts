// Applies SQL migrations from ./drizzle, then (with --seed) loads the base data.
// Usage: tsx scripts/migrate.ts [--seed]      (in Docker: node dist-scripts/migrate.js --seed)
import { createDb } from "../src/db/client";
import { runMigrations } from "../src/db/migrate";
import { seed } from "../src/db/seed";

async function main() {
  const url = process.env.DATABASE_URL;
  if (!url) throw new Error("DATABASE_URL is not set");
  const { db, pool } = createDb(url);
  try {
    await runMigrations(db);
    console.info("migrations applied");
    if (process.argv.includes("--seed")) {
      await seed(db);
      console.info("seed data loaded");
    }
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
