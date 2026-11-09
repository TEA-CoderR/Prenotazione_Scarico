import { updateEntity } from "@/lib/admin/service";
import { adminRoute } from "../../handler";

// PUT /api/admin/entities/:id {legalName, isActive}
export const PUT = adminRoute(({ db, user, id, body }) => updateEntity(db, user, id, body as { legalName?: string; isActive: boolean }));
