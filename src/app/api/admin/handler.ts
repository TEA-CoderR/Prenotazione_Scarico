import type { NextRequest } from "next/server";
import { getDb, type Db } from "@/db/client";
import type { UserRole } from "@/db/schema";
import type { StaffUser } from "@/lib/auth/session";
import { bookingErrorResponse } from "../errors";
import { rejectCrossSite, requireStaff } from "../staff/auth";

type Ctx = { params: Promise<Record<string, string>> };
type Handler = (args: { db: Db; user: StaffUser; body: unknown; id: number; query: URLSearchParams }) => Promise<unknown>;

/**
 * Wraps an admin endpoint: sign-in + 2FA + role check, same-origin JSON for writes,
 * numeric :id parsing, and BookingError → JSON error.
 */
export function adminRoute(handler: Handler, roles: UserRole[] = ["admin"]) {
  return async (request: NextRequest, ctx: Ctx) => {
    const write = request.method !== "GET";
    if (write) {
      const blocked = rejectCrossSite(request);
      if (blocked) return blocked;
    }
    const user = await requireStaff(request, { roles });
    if (user instanceof Response) return user;
    const params = await ctx.params;
    const id = params?.id !== undefined ? Number(params.id) : NaN;
    if (params?.id !== undefined && !Number.isInteger(id)) return Response.json({ error: "VALIDATION" }, { status: 400 });
    const body = write ? await request.json().catch(() => null) : null;
    try {
      const result = await handler({ db: getDb(), user, body, id, query: request.nextUrl.searchParams });
      if (result instanceof Response) return result; // e.g. a file download
      return Response.json(result ?? { ok: true });
    } catch (err) {
      return bookingErrorResponse(err);
    }
  };
}
