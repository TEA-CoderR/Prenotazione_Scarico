import { connection, NextResponse, type NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { env } from "@/env";
import type { UserRole } from "@/db/schema";
import { getSessionUser, SESSION_COOKIE, type StaffUser } from "@/lib/auth/session";

export function sessionCookieOptions(expires: Date) {
  return {
    httpOnly: true,
    secure: env().APP_URL.startsWith("https://"),
    sameSite: "lax" as const,
    path: "/",
    expires,
  };
}

/**
 * The signed-in staff member, or an error response to return as is:
 * 401 when not signed in, 403 MFA_REQUIRED until the second factor is done
 * (pass allowMfaPending for the setup endpoints themselves).
 */
export async function requireStaff(
  request: NextRequest,
  opts: { allowMfaPending?: boolean; roles?: UserRole[] } = {},
): Promise<StaffUser | Response> {
  await connection(); // per-request data: never prerender
  const user = await getSessionUser(getDb(), request.cookies.get(SESSION_COOKIE)?.value);
  if (!user) return NextResponse.json({ error: "UNAUTHENTICATED" }, { status: 401 });
  if (!user.mfaVerified && !opts.allowMfaPending) return NextResponse.json({ error: "MFA_REQUIRED" }, { status: 403 });
  if (opts.roles && !opts.roles.includes(user.role)) return NextResponse.json({ error: "FORBIDDEN" }, { status: 403 });
  return user;
}

/**
 * CSRF guard for state-changing staff requests: the cookie is SameSite=Lax, and in addition
 * the request must be JSON and, when the browser sends an Origin, come from this site.
 */
export function rejectCrossSite(request: NextRequest): Response | null {
  const origin = request.headers.get("origin");
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host");
  const json = request.headers.get("content-type")?.includes("application/json");
  if (!json || (origin && host && new URL(origin).host !== host)) {
    return NextResponse.json({ error: "FORBIDDEN" }, { status: 403 });
  }
  return null;
}
