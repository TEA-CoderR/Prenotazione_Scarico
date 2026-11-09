import { randomBytes } from "node:crypto";
import { and, eq, gt, lt } from "drizzle-orm";
import type { Db } from "@/db/client";
import { appUser, site, staffSession, userSite } from "@/db/schema";
import { hashToken } from "@/lib/booking/service";
import { hashPassword, verifyPassword } from "./password";
import type { SessionUser } from "./site-access";

export const SESSION_COOKIE = "sid";
/** Sessions last 14 days and are renewed when used in the second week (tablets at the dock stay signed in). */
export const SESSION_DAYS = 14;
const DAY = 86_400_000;

export interface StaffUser extends SessionUser {
  email: string;
  name: string;
}

// Used when the e-mail is unknown, so a failed login takes about as long either way.
let dummyHash: Promise<string> | undefined;

export async function checkCredentials(db: Db, email: string, password: string): Promise<number | null> {
  const [u] = await db
    .select({ id: appUser.id, hash: appUser.passwordHash, active: appUser.isActive })
    .from(appUser)
    .where(eq(appUser.email, email.trim().toLowerCase()));
  if (!u || !u.hash || !u.active) {
    dummyHash ??= hashPassword("not-a-real-password");
    await verifyPassword(password, await dummyHash);
    return null;
  }
  return (await verifyPassword(password, u.hash)) ? u.id : null;
}

export async function createSession(db: Db, userId: number, now = new Date()): Promise<{ token: string; expiresAt: Date }> {
  const token = randomBytes(32).toString("base64url");
  const expiresAt = new Date(now.getTime() + SESSION_DAYS * DAY);
  await db.insert(staffSession).values({ tokenHash: hashToken(token), userId, createdAt: now, expiresAt });
  // Opportunistic cleanup of this user's expired sessions.
  await db.delete(staffSession).where(and(eq(staffSession.userId, userId), lt(staffSession.expiresAt, now)));
  return { token, expiresAt };
}

/** The signed-in user for a session cookie, or null. Renews sessions older than a week. */
export async function getSessionUser(db: Db, token: string | undefined, now = new Date()): Promise<StaffUser | null> {
  if (!token || token.length > 100) return null;
  const tokenHash = hashToken(token);
  const [row] = await db
    .select({ id: appUser.id, email: appUser.email, name: appUser.name, role: appUser.role, expiresAt: staffSession.expiresAt })
    .from(staffSession)
    .innerJoin(appUser, eq(appUser.id, staffSession.userId))
    .where(and(eq(staffSession.tokenHash, tokenHash), gt(staffSession.expiresAt, now), eq(appUser.isActive, true)));
  if (!row) return null;

  if (row.expiresAt.getTime() - now.getTime() < (SESSION_DAYS / 2) * DAY) {
    await db
      .update(staffSession)
      .set({ expiresAt: new Date(now.getTime() + SESSION_DAYS * DAY) })
      .where(eq(staffSession.tokenHash, tokenHash));
  }
  const sites = await db.select({ siteId: userSite.siteId }).from(userSite).where(eq(userSite.userId, row.id));
  return { id: row.id, email: row.email, name: row.name, role: row.role, siteIds: sites.map((s) => s.siteId) };
}

export async function deleteSession(db: Db, token: string | undefined) {
  if (token) await db.delete(staffSession).where(eq(staffSession.tokenHash, hashToken(token)));
}

/** Sites the user may work on, for the site selector. */
export async function sitesForUser(db: Db, user: SessionUser) {
  const all = await db
    .select({ id: site.id, code: site.code, name: site.name })
    .from(site)
    .where(eq(site.isActive, true))
    .orderBy(site.id);
  return user.role === "admin" ? all : all.filter((s) => user.siteIds.includes(s.id));
}
