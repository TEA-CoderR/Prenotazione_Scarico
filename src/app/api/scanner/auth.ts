import { connection, NextResponse } from "next/server";
import { getDb } from "@/db/client";
import { deviceFor, type DeviceRow } from "@/lib/scanner";

/** The calling scanner (Authorization: Bearer <device key>), or a 401 to return as is. */
export async function requireDevice(request: Request): Promise<DeviceRow | Response> {
  await connection();
  const token = /^Bearer\s+(.+)$/i.exec(request.headers.get("authorization") ?? "")?.[1]?.trim();
  const d = await deviceFor(getDb(), token);
  return d ?? NextResponse.json({ error: "UNAUTHENTICATED" }, { status: 401 });
}
