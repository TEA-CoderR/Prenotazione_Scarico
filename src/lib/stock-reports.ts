// Stock errors reported by the stores from the scanners: the system shows stock that is not on the
// shelf (or a different quantity). The store counts shelf + back room and reports the real number; the
// purchasing office corrects the stock in the ERP (the platform never writes the ERP) and marks the
// report as fixed or rejected. One open report per store and item: a new count replaces it.
import { and, desc, eq, gte, inArray } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "@/db/client";
import { stockReport } from "@/db/schema";
import { BookingError } from "@/lib/booking/errors";

const normEan = (v: string) => v.trim().replace(/^0+(?=\d)/, "");

export const reportSchema = z.object({
  ean: z
    .string()
    .trim()
    .regex(/^\d{1,20}$/)
    .transform(normEan),
  name: z.string().trim().max(120).default(""),
  systemQty: z.number().min(-99999).max(99999).nullable().optional(),
  counted: z.number().int().min(0).max(99999),
  countedBy: z.string().trim().min(2).max(40),
  /** The store confirms it counted the shelf and the back room. */
  shelf: z.literal(true),
  backroom: z.literal(true),
});

type Row = typeof stockReport.$inferSelect;

const view = (r: Row) => ({
  id: r.id,
  entityCode: r.entityCode,
  ean: r.ean,
  name: r.name,
  systemQty: r.systemQty == null ? null : Number(r.systemQty),
  counted: r.countedQty,
  countedBy: r.countedBy,
  device: r.device,
  status: r.status,
  answer: r.answer,
  handledBy: r.handledBy,
  handledAt: r.handledAt?.toISOString() ?? null,
  createdAt: r.createdAt.toISOString(),
  updatedAt: r.updatedAt.toISOString(),
});
export type StockReportView = ReturnType<typeof view>;

/** Scanner side: report the counted quantity (replaces this store's open report of the same item). */
export async function reportStock(db: Db, device: { name: string; entityCode: number | null }, raw: unknown, now = new Date()) {
  if (device.entityCode == null) throw new BookingError("FORBIDDEN", "Questo scanner non è collegato a un negozio");
  const parsed = reportSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", "Conta scaffale e magazzino, poi scrivi chi ha contato");
  const p = parsed.data;
  if (p.systemQty != null && Math.round(p.systemQty) === p.counted) {
    throw new BookingError("VALIDATION", "La giacenza contata è uguale a quella del sistema: niente da segnalare");
  }
  const v = {
    name: p.name,
    systemQty: p.systemQty ?? null,
    countedQty: p.counted,
    countedBy: p.countedBy,
    device: device.name,
    updatedAt: now,
  };
  return db.transaction(async (tx) => {
    const [open] = await tx
      .select()
      .from(stockReport)
      .where(and(eq(stockReport.entityCode, device.entityCode!), eq(stockReport.ean, p.ean), eq(stockReport.status, "open")));
    const [r] = open
      ? await tx.update(stockReport).set({ ...v, createdAt: now }).where(eq(stockReport.id, open.id)).returning()
      : await tx
          .insert(stockReport)
          .values({ entityCode: device.entityCode!, ean: p.ean, ...v, createdAt: now })
          .returning();
    return view(r);
  });
}

/** A store's reports of the last `days` days (newest first): the scanners show their status. */
export async function storeReports(db: Db, entityCode: number | null, now = new Date(), days = 60) {
  if (entityCode == null) return [];
  const rows = await db
    .select()
    .from(stockReport)
    .where(and(eq(stockReport.entityCode, entityCode), gte(stockReport.createdAt, new Date(now.getTime() - days * 86_400_000))))
    .orderBy(desc(stockReport.createdAt))
    .limit(500);
  return rows.map(view);
}

// ------------------------------------------------------------------ purchasing program

export const STATUSES = ["open", "fixed", "rejected"] as const;

export async function erpReports(db: Db, status?: string | null, now = new Date(), days = 90) {
  const conds = [gte(stockReport.createdAt, new Date(now.getTime() - days * 86_400_000))];
  if (status && status !== "all") conds.push(inArray(stockReport.status, status.split(",")));
  const rows = await db.select().from(stockReport).where(and(...conds)).orderBy(desc(stockReport.createdAt)).limit(2000);
  return rows.map(view);
}

export const handleSchema = z.object({
  action: z.enum(["fixed", "rejected", "reopen"]),
  answer: z.string().trim().max(200).optional(),
  by: z.string().trim().max(80).optional(),
});

/** fixed = stock corrected in the ERP · rejected (with a reason the store sees) · reopen (undo). */
export async function handleReport(db: Db, id: number, raw: unknown, now = new Date()) {
  const parsed = handleSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const { action, answer, by } = parsed.data;
  if (action === "rejected" && !answer) throw new BookingError("VALIDATION", "Rejecting needs a reason");
  const set =
    action === "reopen"
      ? { status: "open", answer: null, handledBy: null, handledAt: null, updatedAt: now }
      : { status: action, answer: answer ?? null, handledBy: by ?? null, handledAt: now, updatedAt: now };
  const [r] = await db.update(stockReport).set(set).where(eq(stockReport.id, id)).returning();
  if (!r) throw new BookingError("NOT_FOUND", "Unknown report");
  return view(r);
}
