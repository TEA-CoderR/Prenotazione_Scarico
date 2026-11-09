import { updateSite, type SiteSettingsInput } from "@/lib/admin/service";
import { adminRoute } from "../../handler";

// PUT /api/admin/sites/:id {name, address, hours[], policy} → {outside: bookings no longer within hours}
export const PUT = adminRoute(({ db, user, id, body }) => updateSite(db, user, id, body as SiteSettingsInput));
