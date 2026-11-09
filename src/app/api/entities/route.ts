import { connection } from "next/server";
import { getDb } from "@/db/client";
import { listEntities } from "@/lib/booking/carrier";

export async function GET() {
  await connection();
  return Response.json({ entities: await listEntities(getDb()) });
}
