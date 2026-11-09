import { listAudit } from "@/lib/admin/service";
import { adminRoute } from "../handler";

export const GET = adminRoute(async ({ db, user }) => ({ entries: await listAudit(db, user) }));
