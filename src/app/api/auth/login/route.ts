import { NextResponse, type NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { authenticate, createSession, SESSION_COOKIE } from "@/lib/auth/session";
import { clientIp, rateLimit } from "@/lib/rate-limit";
import { tooManyRequests } from "../../errors";
import { rejectCrossSite, sessionCookieOptions } from "../../staff/auth";

const body = z.object({
  email: z.string().trim().toLowerCase().max(200),
  password: z.string().max(200),
  code: z.string().trim().max(10).optional(),
});

const ERRORS = {
  bad_credentials: ["BAD_CREDENTIALS", 401],
  totp_required: ["TOTP_REQUIRED", 401],
  bad_totp: ["BAD_TOTP", 401],
} as const;

// POST /api/auth/login {email, password, code?}
// → 200 {mfaSetupRequired}; 401 BAD_CREDENTIALS | TOTP_REQUIRED (ask for the code) | BAD_TOTP
export async function POST(request: NextRequest) {
  const blocked = rejectCrossSite(request);
  if (blocked) return blocked;
  const parsed = body.safeParse(await request.json().catch(() => null));
  if (!parsed.success) return NextResponse.json({ error: "VALIDATION" }, { status: 400 });
  const { email, password, code } = parsed.data;

  // Slow down guessing of passwords and codes, per address and per IP.
  const ip = clientIp(request.headers);
  if (!rateLimit(`login-ip:${ip}`, 30, 15 * 60_000) || !rateLimit(`login:${email}`, 8, 15 * 60_000)) {
    return tooManyRequests();
  }

  const db = getDb();
  const result = await authenticate(db, email, password, code || undefined);
  if (result.status !== "ok") {
    const [error, status] = ERRORS[result.status];
    return NextResponse.json({ error }, { status });
  }
  const { token, expiresAt } = await createSession(db, result.userId, new Date(), result.mfaVerified);
  const res = NextResponse.json({ ok: true, mfaSetupRequired: !result.mfaVerified });
  res.cookies.set(SESSION_COOKIE, token, sessionCookieOptions(expiresAt));
  return res;
}
