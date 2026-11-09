import { deleteClosure } from "@/lib/admin/service";
import { adminRoute } from "../../handler";

export const DELETE = adminRoute(({ db, user, id }) => deleteClosure(db, user, id), ["admin", "warehouse_manager"]);
