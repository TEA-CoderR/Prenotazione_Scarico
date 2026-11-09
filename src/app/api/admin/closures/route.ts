import { addClosure, listClosures } from "@/lib/admin/service";
import { adminRoute } from "../handler";

const roles = ["admin", "warehouse_manager"] as const;

// GET /api/admin/closures → upcoming closed days (managers: their sites + national holidays)
export const GET = adminRoute(async ({ db, user }) => ({ closures: await listClosures(db, user) }), [...roles]);

// POST /api/admin/closures {siteId | null, date, reason} → {affected: bookings already on that day}
export const POST = adminRoute(({ db, user, body }) => addClosure(db, user, body as Parameters<typeof addClosure>[2]), [...roles]);
