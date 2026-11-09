import type { NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { beginTotpSetup, confirmTotpSetup, SESSION_COOKIE } from "@/lib/auth/session";
import { rateLimit } from "@/lib/rate-limit";
import { tooManyRequests } from "../../errors";
import { rejectCrossSite, requireStaff } from "../../staff/auth";

// GET /api/auth/2fa → a new secret + QR code to scan (only while 2FA is not set up yet)
export async function GET(request: NextRequest) {
  const user = await requireStaff(request, { allowMfaPending: true });
  if (user instanceof Response) return user;
  if (user.hasTotp) return Response.json({ error: "ALREADY_ENABLED" }, { status: 409 });
  return Response.json(await beginTotpSetup(getDb(), user));
}

// POST /api/auth/2fa {code} → activates it and unlocks this session
export async function POST(request: NextRequest) {
  const blocked = rejectCrossSite(request);
  if (blocked) return blocked;
  const user = await requireStaff(request, { allowMfaPending: true });
  if (user instanceof Response) return user;
  if (!rateLimit(`2fa-setup:${user.id}`, 10, 15 * 60_000)) return tooManyRequests();
  const parsed = z.object({ code: z.string().max(10) }).safeParse(await request.json().catch(() => null));
  if (!parsed.success) return Response.json({ error: "VALIDATION" }, { status: 400 });

  const ok = await confirmTotpSetup(getDb(), user, request.cookies.get(SESSION_COOKIE)!.value, parsed.data.code);
  return ok ? Response.json({ ok: true }) : Response.json({ error: "BAD_TOTP" }, { status: 400 });
}
