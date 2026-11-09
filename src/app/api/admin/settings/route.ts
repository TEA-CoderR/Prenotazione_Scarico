import { getSettings } from "@/lib/admin/service";
import { adminRoute } from "../handler";

// GET /api/admin/settings → sites (hours, rules), recipients, durations
export const GET = adminRoute(({ db, user }) => getSettings(db, user));
