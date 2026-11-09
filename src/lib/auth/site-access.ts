import type { UserRole } from "@/db/schema";
import { BookingError } from "@/lib/booking/errors";

export interface SessionUser {
  id: number;
  role: UserRole;
  /** Sites from user_site. Ignored for admins. */
  siteIds: number[];
}

/** Warehouse staff only see their own sites; staff of the shared site see entities 0 and 3. */
export function canAccessSite(user: SessionUser, siteId: number): boolean {
  return user.role === "admin" || user.siteIds.includes(siteId);
}

export function assertCanAccessSite(user: SessionUser, siteId: number): void {
  if (!canAccessSite(user, siteId)) throw new BookingError("FORBIDDEN", "No access to this site");
}
