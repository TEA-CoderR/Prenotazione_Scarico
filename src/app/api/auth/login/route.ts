import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { checkCredentials, createSession, SESSION_COOKIE } from "@/lib/auth/session";
import { clientIp, rateLimit } from "@/lib/rate-limit";
import { tooManyRequests } from "../../errors";
import { rejectCrossSite, sessionCookieOptions } from "../../staff/auth";

const body = z.object({ email: z.string().trim().toLowerCase().max(200), password: z.string().max(200) });

export async function POST(request: NextRequest) {
  const blocked = rejectCrossSite(request);
  if (blocked) return blocked;
  const parsed = body.safeParse(await request.json().catch(() => null));
  if (!parsed.success) return NextResponse.json({ error: "VALIDATION" }, { status: 400 });

  // Slow down password guessing per address and per IP.
  const ip = clientIp(request.headers);
  if (!rateLimit(`login-ip:${ip}`, 30, 15 * 60_000) || !rateLimit(`login:${parsed.data.email}`, 8, 15 * 60_000)) {
    return tooManyRequests();
  }

  const db = getDb();
  const userId = await checkCredentials(db, parsed.data.email, parsed.data.password);
  if (!userId) return NextResponse.json({ error: "BAD_CREDENTIALS" }, { status: 401 });

  const { token, expiresAt } = await createSession(db, userId);
  const res = NextResponse.json({ ok: true });
  res.cookies.set(SESSION_COOKIE, token, sessionCookieOptions(expiresAt));
  return res;
}
