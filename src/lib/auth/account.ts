import { and, eq, ne } from "drizzle-orm";
import type { Db } from "@/db/client";
import { appUser, auditLog, staffSession } from "@/db/schema";
import { BookingError } from "@/lib/booking/errors";
import { hashToken } from "@/lib/booking/service";
import { hashPassword, passwordProblem, verifyPassword } from "./password";
import type { StaffUser } from "./session";

/**
 * Changes the signed-in user's password. Requires the current one, and signs out
 * every other device (this one stays signed in).
 */
export async function changeOwnPassword(db: Db, user: StaffUser, sessionToken: string, current: string, next: string) {
  const [u] = await db.select({ hash: appUser.passwordHash }).from(appUser).where(eq(appUser.id, user.id));
  if (!u?.hash || !(await verifyPassword(current, u.hash))) throw new BookingError("FORBIDDEN", "Current password is wrong");
  const problem = passwordProblem(next);
  if (problem) throw new BookingError("VALIDATION", problem);
  if (next === current) throw new BookingError("VALIDATION", "La nuova password deve essere diversa");

  const passwordHash = await hashPassword(next);
  await db.transaction(async (tx) => {
    await tx.update(appUser).set({ passwordHash }).where(eq(appUser.id, user.id));
    await tx.delete(staffSession).where(and(eq(staffSession.userId, user.id), ne(staffSession.tokenHash, hashToken(sessionToken))));
    await tx.insert(auditLog).values({ userId: user.id, action: "user.change_password", target: `user:${user.id}` });
  });
}
