import { createHash, timingSafeEqual } from "node:crypto";
import { connection, NextResponse } from "next/server";
import { env } from "@/env";

const digest = (s: string) => createHash("sha256").update(s).digest();

/**
 * The purchasing program authenticates with "Authorization: Bearer <ERP_API_TOKEN>".
 * Returns an error response to send as is, or null when the key is right.
 */
export async function requireErp(request: Request): Promise<Response | null> {
  await connection(); // per-request data: never prerender
  const key = env().ERP_API_TOKEN?.trim();
  if (!key || key.length < 32) return NextResponse.json({ error: "ERP_API_DISABLED" }, { status: 503 });
  const got = /^Bearer\s+(.+)$/i.exec(request.headers.get("authorization") ?? "")?.[1]?.trim() ?? "";
  if (!timingSafeEqual(digest(got), digest(key))) return NextResponse.json({ error: "UNAUTHENTICATED" }, { status: 401 });
  return null;
}
