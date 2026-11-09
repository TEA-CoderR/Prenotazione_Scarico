import { z } from "zod";
import { resetPassword, resetTotp, updateUser } from "@/lib/admin/service";
import { BookingError } from "@/lib/booking/errors";
import { adminRoute } from "../../handler";

// PUT {name, role, siteIds, isActive}
export const PUT = adminRoute(({ db, user, id, body }) => updateUser(db, user, id, body as Parameters<typeof updateUser>[3]));

// POST {action: "reset-password"} → {password} | {action: "reset-2fa"}
export const POST = adminRoute(async ({ db, user, id, body }) => {
  const parsed = z.object({ action: z.enum(["reset-password", "reset-2fa"]) }).safeParse(body);
  if (!parsed.success) throw new BookingError("VALIDATION", "Unknown action");
  return parsed.data.action === "reset-password" ? resetPassword(db, user, id) : resetTotp(db, user, id);
});
