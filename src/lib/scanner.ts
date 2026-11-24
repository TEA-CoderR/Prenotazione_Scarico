// Store scanners (Zebra TC22 with the "CASA&TE Scanner" app) and the product catalog they look up.
// Devices are created from the purchasing program (one account per device): it gets a one-time
// pairing code, typed once on the device, which then holds its own key.
import { createHash, randomBytes, randomInt } from "node:crypto";
import { and, desc, eq, gt, lt, notInArray, sql } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "@/db/client";
import { catalogPart, catalogSnapshot, scannerDevice } from "@/db/schema";
import { BookingError } from "@/lib/booking/errors";

const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // no 0/O, 1/I/L
export const PAIR_DAYS = 7;
export const KEEP_SNAPSHOTS = 3;

function pairCode(): string {
  let s = "";
  for (let i = 0; i < 8; i++) s += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return s;
}

/** "abcd-2345" or "ABCD 2345" → "ABCD2345" */
export function normCode(code: string): string {
  return code.toUpperCase().replace(/[^0-9A-Z]/g, "");
}

export interface DeviceRow {
  id: number;
  name: string;
  entityCode: number | null;
  active: boolean;
  paired: boolean;
  pairExpiresAt: string | null;
  createdAt: string;
  lastSeenAt: string | null;
}

const toRow = (d: typeof scannerDevice.$inferSelect): DeviceRow => ({
  id: d.id,
  name: d.name,
  entityCode: d.entityCode,
  active: d.isActive,
  paired: !!d.tokenHash,
  pairExpiresAt: d.pairExpiresAt?.toISOString() ?? null,
  createdAt: d.createdAt.toISOString(),
  lastSeenAt: d.lastSeenAt?.toISOString() ?? null,
});

export const deviceSchema = z.object({
  name: z.string().trim().min(1).max(60),
  entityCode: z.number().int().min(0).max(99).nullable().optional(),
});

/** New device: returns the pairing code (shown once, valid PAIR_DAYS days). */
export async function createDevice(db: Db, raw: z.input<typeof deviceSchema>, now = new Date()) {
  const parsed = deviceSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const code = pairCode();
  const [d] = await db
    .insert(scannerDevice)
    .values({
      name: parsed.data.name,
      entityCode: parsed.data.entityCode ?? null,
      pairCodeHash: sha(code),
      pairExpiresAt: new Date(now.getTime() + PAIR_DAYS * 86_400_000),
      createdAt: now,
    })
    .returning();
  return { device: toRow(d), pairCode: code };
}

export async function listDevices(db: Db): Promise<DeviceRow[]> {
  const rows = await db.select().from(scannerDevice).orderBy(scannerDevice.id);
  return rows.map(toRow);
}

/** New pairing code for a device (lost / reset scanner): the old key stops working. */
export async function renewCode(db: Db, id: number, now = new Date()) {
  const code = pairCode();
  const [d] = await db
    .update(scannerDevice)
    .set({ tokenHash: null, pairCodeHash: sha(code), pairExpiresAt: new Date(now.getTime() + PAIR_DAYS * 86_400_000), isActive: true })
    .where(eq(scannerDevice.id, id))
    .returning();
  if (!d) throw new BookingError("NOT_FOUND", "Unknown scanner");
  return { device: toRow(d), pairCode: code };
}

export async function setActive(db: Db, id: number, active: boolean) {
  const [d] = await db
    .update(scannerDevice)
    .set(active ? { isActive: true } : { isActive: false, tokenHash: null, pairCodeHash: null, pairExpiresAt: null })
    .where(eq(scannerDevice.id, id))
    .returning();
  if (!d) throw new BookingError("NOT_FOUND", "Unknown scanner");
  return toRow(d);
}

/** Rename a device and/or move it to another store (the pairing stays valid). */
export async function updateDevice(db: Db, id: number, raw: { name?: string; entityCode?: number | null }) {
  const parsed = deviceSchema.partial().safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const set: Partial<typeof scannerDevice.$inferInsert> = {};
  if (parsed.data.name !== undefined) set.name = parsed.data.name;
  if (parsed.data.entityCode !== undefined) set.entityCode = parsed.data.entityCode;
  if (!Object.keys(set).length) throw new BookingError("VALIDATION", "Nothing to change");
  const [d] = await db.update(scannerDevice).set(set).where(eq(scannerDevice.id, id)).returning();
  if (!d) throw new BookingError("NOT_FOUND", "Unknown scanner");
  return toRow(d);
}

/** Remove a device for good. Only deactivated ones, so a scanner in use is never cut off by mistake. */
export async function deleteDevice(db: Db, id: number) {
  const [d] = await db.select().from(scannerDevice).where(eq(scannerDevice.id, id));
  if (!d) throw new BookingError("NOT_FOUND", "Unknown scanner");
  if (d.isActive) throw new BookingError("CONFLICT", "Deactivate the scanner before deleting it");
  await db.delete(scannerDevice).where(and(eq(scannerDevice.id, id), eq(scannerDevice.isActive, false)));
  return { deleted: id };
}

/** Device side: exchange the pairing code for the device key (the code works once). */
export async function pair(db: Db, code: string, now = new Date()) {
  const c = normCode(code);
  if (c.length !== 8) throw new BookingError("VALIDATION", "Codice non valido");
  const token = randomBytes(32).toString("base64url");
  const [d] = await db
    .update(scannerDevice)
    .set({ tokenHash: sha(token), pairCodeHash: null, pairExpiresAt: null, pairedAt: now, lastSeenAt: now })
    .where(
      and(eq(scannerDevice.pairCodeHash, sha(c)), eq(scannerDevice.isActive, true), gt(scannerDevice.pairExpiresAt, now)),
    )
    .returning();
  if (!d) throw new BookingError("NOT_FOUND", "Codice non valido o scaduto");
  return { token, device: toRow(d) };
}

/** Device side: who is calling (null = unknown / deactivated). Records when it was last seen. */
export async function deviceFor(db: Db, token: string | null | undefined, now = new Date()) {
  if (!token) return null;
  const [d] = await db
    .update(scannerDevice)
    .set({ lastSeenAt: now })
    .where(and(eq(scannerDevice.tokenHash, sha(token)), eq(scannerDevice.isActive, true)))
    .returning();
  return d ? toRow(d) : null;
}

// ------------------------------------------------------------------ catalog

/**
 * Catalog payload (compact, as the app reads it):
 *  { week, publishedAt, sources: [wholesaler names], directs: [direct supplier names],
 *    items: [[ean, name, griglia, wholesalerIdx[], substituteEan, substituteName, directIdx|-1]],
 *    stores: { "<entity code>": [[ean, stock, sold30, inTransitBoxes, suggestedBoxes, cost, price, promoPrice, promoEnd, pack]] } }
 * griglia: 0 = not in the DETERSIVO Griglia, 1 = in the Griglia, 2 = in the Griglia but discontinued (crossed out).
 * A scanner downloads `items` plus only its own store's rows (as `store`, see catalogFor).
 */
export const partSchema = z.object({
  upload: z.string().regex(/^[A-Za-z0-9_-]{8,64}$/),
  part: z.number().int().min(1).max(200),
  parts: z.number().int().min(1).max(200),
  meta: z.record(z.string(), z.unknown()).optional(),
  /** Set on parts that carry one store's own data (stock, sales, prices…) instead of catalog items. */
  store: z.number().int().min(0).max(99).optional(),
  items: z.array(z.array(z.unknown()).min(2).max(16)).max(20_000),
});

/**
 * One part of a catalog upload. When the last missing part arrives the parts are joined into a new
 * snapshot (meta from part 1) and old snapshots beyond KEEP_SNAPSHOTS are dropped.
 * Returns {complete, id?, items?}.
 */
export async function putCatalogPart(db: Db, raw: z.input<typeof partSchema>, by: string, now = new Date()) {
  const parsed = partSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const p = parsed.data;
  if (p.part > p.parts) throw new BookingError("VALIDATION", "part > parts");
  return db.transaction(async (tx) => {
    // Parts of abandoned uploads older than a day.
    await tx.delete(catalogPart).where(lt(catalogPart.createdAt, new Date(now.getTime() - 86_400_000)));
    await tx
      .insert(catalogPart)
      .values({ uploadId: p.upload, part: p.part, parts: p.parts, payload: { meta: p.meta ?? null, store: p.store ?? null, items: p.items }, createdAt: now })
      .onConflictDoUpdate({
        target: [catalogPart.uploadId, catalogPart.part],
        set: { payload: { meta: p.meta ?? null, store: p.store ?? null, items: p.items } },
      });
    // Serialise the "is it complete?" check per upload.
    await tx.execute(sql`select pg_advisory_xact_lock(hashtext(${p.upload}))`);
    const got = await tx
      .select({ part: catalogPart.part, payload: catalogPart.payload })
      .from(catalogPart)
      .where(eq(catalogPart.uploadId, p.upload))
      .orderBy(catalogPart.part);
    if (got.length < p.parts) return { complete: false as const, received: got.length };
    const items: unknown[] = [];
    const stores: Record<string, unknown[]> = {};
    let meta: Record<string, unknown> = {};
    for (const g of got) {
      const pl = g.payload as { meta: Record<string, unknown> | null; store?: number | null; items: unknown[] };
      if (g.part === 1 && pl.meta) meta = pl.meta;
      if (pl.store != null) (stores[String(pl.store)] ??= []).push(...pl.items);
      else items.push(...pl.items);
    }
    const payload = { ...meta, publishedAt: now.toISOString(), items, stores };
    const [snap] = await tx
      .insert(catalogSnapshot)
      .values({ createdBy: by, itemCount: items.length, payload, createdAt: now })
      .returning({ id: catalogSnapshot.id });
    await tx.delete(catalogPart).where(eq(catalogPart.uploadId, p.upload));
    const keep = await tx
      .select({ id: catalogSnapshot.id })
      .from(catalogSnapshot)
      .orderBy(desc(catalogSnapshot.id))
      .limit(KEEP_SNAPSHOTS);
    await tx.delete(catalogSnapshot).where(notInArray(catalogSnapshot.id, keep.map((k) => k.id)));
    return { complete: true as const, id: snap.id, items: items.length };
  });
}

export async function latestCatalogInfo(db: Db) {
  const [s] = await db
    .select({ id: catalogSnapshot.id, createdAt: catalogSnapshot.createdAt, createdBy: catalogSnapshot.createdBy, itemCount: catalogSnapshot.itemCount })
    .from(catalogSnapshot)
    .orderBy(desc(catalogSnapshot.id))
    .limit(1);
  return s ?? null;
}

/** What one scanner downloads: the catalog plus only its own store's data (as `store`). */
export function catalogFor(payload: unknown, entityCode: number | null) {
  const { stores, ...rest } = (payload ?? {}) as { stores?: Record<string, unknown[]> } & Record<string, unknown>;
  return { ...rest, store: (entityCode != null && stores?.[String(entityCode)]) || [] };
}

export async function latestCatalog(db: Db) {
  const [s] = await db.select().from(catalogSnapshot).orderBy(desc(catalogSnapshot.id)).limit(1);
  return s ?? null;
}
