import { updateSlotRules } from "@/lib/admin/service";
import { adminRoute } from "../handler";

// PUT /api/admin/slot-rules [{vehicleType, maxPallets, maxParcels, durationMin}]
export const PUT = adminRoute(({ db, user, body }) => updateSlotRules(db, user, body as Parameters<typeof updateSlotRules>[2]));
