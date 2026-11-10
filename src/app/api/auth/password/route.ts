import type { NextRequest } from "next/server";
import { z } from "zod";
import { getDb } from "@/db/client";
import { changeOwnPassword } from "@/lib/auth/account";
import { SESSION_COOKIE } from "@/lib/auth/session";
import { rateLimit } from "@/lib/rate-limit";
import { bookingErrorResponse, tooManyRequests } from "../../errors";
import { rejectCrossSite, requireStaff } from "../../staff/auth";

const body = z.object({ current: z.string().max(200), next: z.string().max(200) });

// POST /api/auth/password {current, next} → changes the signed-in user's password
export async function POST(request: NextRequest) {
  const blocked = rejectCrossSite(request);
  if (blocked) return blocked;
  const user = await requireStaff(request);
  if (user instanceof Response) return user;
  if (!rateLimit(`password:${user.id}`, 5, 15 * 60_000)) return tooManyRequests();
  const parsed = body.safeParse(await request.json().catch(() => null));
  if (!parsed.success) return Response.json({ error: "VALIDATION" }, { status: 400 });
  try {
    await changeOwnPassword(getDb(), user, request.cookies.get(SESSION_COOKIE)!.value, parsed.data.current, parsed.data.next);
    return Response.json({ ok: true });
  } catch (err) {
    return bookingErrorResponse(err);
  }
}
