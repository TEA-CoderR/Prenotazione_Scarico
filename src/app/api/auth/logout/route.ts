import { NextResponse, type NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { deleteSession, SESSION_COOKIE } from "@/lib/auth/session";
import { rejectCrossSite } from "../../staff/auth";

export async function POST(request: NextRequest) {
  const blocked = rejectCrossSite(request);
  if (blocked) return blocked;
  await deleteSession(getDb(), request.cookies.get(SESSION_COOKIE)?.value);
  const res = NextResponse.json({ ok: true });
  res.cookies.delete(SESSION_COOKIE);
  return res;
}
