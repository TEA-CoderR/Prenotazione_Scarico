import { bookingsCsv, loadReportRows } from "@/lib/reports/service";
import { adminRoute } from "../../handler";
import { reportQuery } from "../query";

// GET /api/admin/reports/export?from=…&to=…&site=… → CSV for Excel (contains personal data)
export const GET = adminRoute(
  async ({ db, user, query }) => {
    const q = reportQuery(query);
    const csv = bookingsCsv(await loadReportRows(db, user, q));
    return new Response(csv, {
      headers: {
        "content-type": "text/csv; charset=utf-8",
        "content-disposition": `attachment; filename="prenotazioni_${q.from}_${q.to}.csv"`,
        "cache-control": "no-store",
      },
    });
  },
  ["admin", "warehouse_manager"],
);
