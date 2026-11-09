import { createUser, listUsers } from "@/lib/admin/service";
import { adminRoute } from "../handler";

export const GET = adminRoute(async ({ db, user }) => ({ users: await listUsers(db, user) }));

// POST {email, name, role, siteIds} → {id, password}  (temporary password, shown once)
export const POST = adminRoute(({ db, user, body }) => createUser(db, user, body as Parameters<typeof createUser>[2]));
