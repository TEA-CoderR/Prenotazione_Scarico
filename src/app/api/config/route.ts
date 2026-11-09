import { connection } from "next/server";
import { getDb } from "@/db/client";
import { env } from "@/env";
import { publicSlotRules } from "@/lib/booking/carrier";

// Public runtime settings for the browser (read at request time, so the Docker image
// does not need rebuilding when a key changes).
export async function GET() {
  await connection();
  return Response.json({ turnstileSiteKey: env().TURNSTILE_SITE_KEY ?? null, slotRules: await publicSlotRules(getDb()) });
}
