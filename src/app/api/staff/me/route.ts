import type { NextRequest } from "next/server";
import { getDb } from "@/db/client";
import { sitesForUser } from "@/lib/auth/session";
import { requireStaff } from "../auth";

// GET /api/staff/me → the signed-in user and the warehouses they can work on
export async function GET(request: NextRequest) {
  const user = await requireStaff(request);
  if (user instanceof Response) return user;
  return Response.json({
    user: { name: user.name, email: user.email, role: user.role },
    sites: await sitesForUser(getDb(), user),
  });
}
