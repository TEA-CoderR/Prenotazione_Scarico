import { randomBytes } from "node:crypto";
import { and, eq, gt, lt } from "drizzle-orm";
import type { Db } from "@/db/client";
import { appUser, site, staffSession, userSite } from "@/db/schema";
import { hashToken } from "@/lib/booking/service";
import QRCode from "qrcode";
import { hashPassword, verifyPassword } from "./password";
import { generateSecret, otpauthUrl, verifyTotp } from "./totp";
import type { SessionUser } from "./site-access";

export const SESSION_COOKIE = "sid";
/** Sessions last 14 days and are renewed when used in the second week (tablets at the dock stay signed in). */
export const SESSION_DAYS = 14;
const DAY = 86_400_000;

export interface StaffUser extends SessionUser {
  email: string;
  name: string;
  /** False until the second factor was given (or set up for the first time) on this session. */
  mfaVerified: boolean;
  hasTotp: boolean;
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

export type AuthResult =
  | { status: "ok"; userId: number; mfaVerified: boolean }
  | { status: "bad_credentials" | "totp_required" | "bad_totp" };

/**
 * Password, then the authenticator code for users who have set one up. Users without
 * one get a session that can only be used to set it up (mfaVerified = false).
 */
export async function authenticate(db: Db, email: string, password: string, code: string | undefined, now = new Date()): Promise<AuthResult> {
  const userId = await checkCredentials(db, email, password);
  if (!userId) return { status: "bad_credentials" };
  const [u] = await db.select({ secret: appUser.totpSecret, last: appUser.totpLastStep }).from(appUser).where(eq(appUser.id, userId));
  if (!u.secret) return { status: "ok", userId, mfaVerified: false };
  if (!code) return { status: "totp_required" };
  const step = verifyTotp(u.secret, code, now, u.last);
  if (step === null) return { status: "bad_totp" };
  await db.update(appUser).set({ totpLastStep: step }).where(eq(appUser.id, userId));
  return { status: "ok", userId, mfaVerified: true };
}

export async function createSession(
  db: Db,
  userId: number,
  now = new Date(),
  mfaVerified = false,
): Promise<{ token: string; expiresAt: Date }> {
  const token = randomBytes(32).toString("base64url");
  const expiresAt = new Date(now.getTime() + SESSION_DAYS * DAY);
  await db.insert(staffSession).values({ tokenHash: hashToken(token), userId, createdAt: now, expiresAt, mfaVerified });
  // Opportunistic cleanup of this user's expired sessions.
  await db.delete(staffSession).where(and(eq(staffSession.userId, userId), lt(staffSession.expiresAt, now)));
  return { token, expiresAt };
}

/** The signed-in user for a session cookie, or null. Renews sessions older than a week. */
export async function getSessionUser(db: Db, token: string | undefined, now = new Date()): Promise<StaffUser | null> {
  if (!token || token.length > 100) return null;
  const tokenHash = hashToken(token);
  const [row] = await db
    .select({
      id: appUser.id,
      email: appUser.email,
      name: appUser.name,
      role: appUser.role,
      totpSecret: appUser.totpSecret,
      expiresAt: staffSession.expiresAt,
      mfaVerified: staffSession.mfaVerified,
    })
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
  return {
    id: row.id,
    email: row.email,
    name: row.name,
    role: row.role,
    siteIds: sites.map((s) => s.siteId),
    mfaVerified: row.mfaVerified,
    hasTotp: !!row.totpSecret,
  };
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

/** First-time authenticator setup: a new secret, shown as a QR code and as text. */
export async function beginTotpSetup(db: Db, user: StaffUser) {
  if (user.hasTotp) throw new Error("Two-factor authentication is already set up");
  const secret = generateSecret();
  await db.update(appUser).set({ totpPendingSecret: secret }).where(eq(appUser.id, user.id));
  const url = otpauthUrl(secret, user.email);
  return { secret, otpauthUrl: url, qrSvg: await QRCode.toString(url, { type: "svg", margin: 1, errorCorrectionLevel: "M" }) };
}

/** Activates the pending secret if the code matches, and unlocks the current session. */
export async function confirmTotpSetup(db: Db, user: StaffUser, sessionToken: string, code: string, now = new Date()): Promise<boolean> {
  const [u] = await db.select({ pending: appUser.totpPendingSecret, secret: appUser.totpSecret }).from(appUser).where(eq(appUser.id, user.id));
  if (!u?.pending || u.secret) return false;
  const step = verifyTotp(u.pending, code, now);
  if (step === null) return false;
  await db.transaction(async (tx) => {
    await tx.update(appUser).set({ totpSecret: u.pending, totpPendingSecret: null, totpLastStep: step }).where(eq(appUser.id, user.id));
    await tx.update(staffSession).set({ mfaVerified: true }).where(eq(staffSession.tokenHash, hashToken(sessionToken)));
  });
  return true;
}
