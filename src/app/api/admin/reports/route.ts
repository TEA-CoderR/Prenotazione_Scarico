import { getReport } from "@/lib/reports/service";
import { adminRoute } from "../handler";
import { reportQuery } from "./query";

// GET /api/admin/reports?from=2026-10-01&to=2026-10-31&site=all
export const GET = adminRoute(({ db, user, query }) => getReport(db, user, reportQuery(query)), ["admin", "warehouse_manager"]);
