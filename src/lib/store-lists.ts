// Store DETERSIVO lists made on the scanners. One shared list per store per week: every scanner of the
// store adds to it, the list can be changed until the weekly deadline (set by the purchasing office,
// default Sunday 10:00), after the deadline new scans go into next week's list. The purchasing program
// downloads the lists, marks them received and later sends back what was actually ordered (feedback).
import { and, desc, eq, gte, inArray, lt } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "@/db/client";
import { appSetting, storeList, storeListLine } from "@/db/schema";
import { BookingError } from "@/lib/booking/errors";
import { storeReports } from "@/lib/stock-reports";
import { addDays, isoWeekday, localDate, zonedToUtc } from "@/lib/time";

export const TZ = "Europe/Rome";
const RULE_KEY = "store_list_deadline";
export const KINDS = ["griglia", "sub", "urgent", "remind", "other"] as const;

export const ruleSchema = z.object({
  day: z.number().int().min(1).max(7), // ISO weekday: 1 = Monday … 7 = Sunday
  time: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/),
});
export type DeadlineRule = z.infer<typeof ruleSchema>;
export const DEFAULT_RULE: DeadlineRule = { day: 7, time: "10:00" };

export async function getRule(db: Db): Promise<DeadlineRule> {
  const [r] = await db.select().from(appSetting).where(eq(appSetting.key, RULE_KEY));
  const parsed = ruleSchema.safeParse(r?.value);
  return parsed.success ? parsed.data : DEFAULT_RULE;
}

export async function setRule(db: Db, raw: unknown, now = new Date()): Promise<DeadlineRule> {
  const parsed = ruleSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  await db
    .insert(appSetting)
    .values({ key: RULE_KEY, value: parsed.data, updatedAt: now })
    .onConflictDoUpdate({ target: appSetting.key, set: { value: parsed.data, updatedAt: now } });
  return parsed.data;
}

/** The list a scan made `now` belongs to: the first deadline after now. week = the deadline's date. */
export function nextDeadline(now: Date, rule: DeadlineRule): { week: string; deadline: Date } {
  const today = localDate(now, TZ);
  for (let i = 0; i <= 7; i++) {
    const d = addDays(today, i);
    if (isoWeekday(d) !== rule.day) continue;
    const at = zonedToUtc(d, rule.time, TZ);
    if (at > now) return { week: d, deadline: at };
  }
  const d = addDays(today, 7);
  return { week: d, deadline: zonedToUtc(d, rule.time, TZ) };
}

/** "08001090213465" / "8001090213465" → "8001090213465" (the catalog's form). */
export function normEan(v: string): string {
  return v.trim().replace(/^0+(?=\d)/, "");
}

export const lineSchema = z.object({
  ean: z
    .string()
    .trim()
    .regex(/^\d{1,20}$/)
    .transform(normEan),
  name: z.string().trim().max(120).default(""),
  boxes: z.number().int().min(0).max(9999),
  kind: z.enum(KINDS).default("griglia"),
  reason: z.string().trim().max(120).nullable().optional(),
  origEan: z.string().trim().max(20).nullable().optional(),
  origName: z.string().trim().max(120).nullable().optional(),
});
export const linesSchema = z.object({ lines: z.array(lineSchema).min(1).max(500) });

type ListRow = typeof storeList.$inferSelect;
type LineRow = typeof storeListLine.$inferSelect;

const lineView = (l: LineRow) => ({
  ean: l.ean,
  name: l.name,
  boxes: l.boxes,
  kind: l.kind,
  reason: l.reason,
  origEan: l.origEan,
  origName: l.origName,
  updatedBy: l.updatedBy,
  updatedAt: l.updatedAt.toISOString(),
});

const listView = (s: ListRow, lines: LineRow[]) => ({
  id: s.id,
  entityCode: s.entityCode,
  week: s.week,
  deadline: s.deadline.toISOString(),
  status: s.status,
  submittedAt: s.submittedAt?.toISOString() ?? null,
  submittedBy: s.submittedBy,
  receivedAt: s.receivedAt?.toISOString() ?? null,
  updatedAt: s.updatedAt.toISOString(),
  feedback: s.feedback ?? null,
  feedbackAt: s.feedbackAt?.toISOString() ?? null,
  lines: lines.map(lineView),
});
export type ListView = ReturnType<typeof listView>;

async function linesOf(db: Db, ids: number[]) {
  if (!ids.length) return new Map<number, LineRow[]>();
  const rows = await db.select().from(storeListLine).where(inArray(storeListLine.listId, ids)).orderBy(storeListLine.updatedAt);
  const m = new Map<number, LineRow[]>();
  for (const r of rows) (m.get(r.listId) ?? m.set(r.listId, []).get(r.listId)!).push(r);
  return m;
}

function needStore(entityCode: number | null): number {
  if (entityCode == null) throw new BookingError("FORBIDDEN", "Questo scanner non è collegato a un negozio");
  return entityCode;
}

async function ensureList(db: Db, entityCode: number, now: Date, rule: DeadlineRule) {
  const { week, deadline } = nextDeadline(now, rule);
  await db.insert(storeList).values({ entityCode, week, deadline, createdAt: now, updatedAt: now }).onConflictDoNothing();
  const [s] = await db
    .select()
    .from(storeList)
    .where(and(eq(storeList.entityCode, entityCode), eq(storeList.week, week)));
  return s;
}

/**
 * What a scanner shows: this week's list of its store (empty if nobody has scanned yet), last week's list
 * with what was ordered, and "remind me" items of the last 4 weeks that are not in this week's list.
 */
export async function currentList(db: Db, entityCode: number | null, now = new Date()) {
  const store = needStore(entityCode);
  const rule = await getRule(db);
  const { week, deadline } = nextDeadline(now, rule);
  const recent = await db
    .select()
    .from(storeList)
    .where(and(eq(storeList.entityCode, store), gte(storeList.week, addDays(week, -28))))
    .orderBy(desc(storeList.week));
  const lines = await linesOf(db, recent.map((r) => r.id));
  const cur = recent.find((r) => r.week === week);
  const prev = recent.find((r) => r.week < week && (lines.get(r.id)?.length ?? 0) > 0);
  const inList = new Set((cur && lines.get(cur.id)?.map((l) => l.ean)) || []);
  const reminders = new Map<string, { ean: string; name: string; week: string }>();
  for (const r of recent) {
    if (r.week >= week) continue;
    for (const l of lines.get(r.id) ?? []) {
      if (l.kind === "remind" && !inList.has(l.ean) && !reminders.has(l.ean)) reminders.set(l.ean, { ean: l.ean, name: l.name, week: r.week });
    }
  }
  return {
    rule,
    week,
    deadline: deadline.toISOString(),
    list: cur ? listView(cur, lines.get(cur.id) ?? []) : null,
    previous: prev ? listView(prev, lines.get(prev.id) ?? []) : null,
    reminders: [...reminders.values()],
    stockReports: await storeReports(db, store, now),
  };
}

/** Scanner side: set lines of this week's list (boxes = the new total; 0 removes the line, except "remind"). */
export async function putLines(db: Db, device: { name: string; entityCode: number | null }, raw: unknown, now = new Date()) {
  const store = needStore(device.entityCode);
  const parsed = linesSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const rule = await getRule(db);
  await db.transaction(async (tx) => {
    const s = await ensureList(tx as unknown as Db, store, now, rule);
    for (const l of parsed.data.lines) {
      if (l.boxes <= 0 && l.kind !== "remind") {
        await tx.delete(storeListLine).where(and(eq(storeListLine.listId, s.id), eq(storeListLine.ean, l.ean)));
        continue;
      }
      if (l.kind === "urgent" && !l.reason) throw new BookingError("VALIDATION", "Un'urgenza ha bisogno di un motivo");
      const v = {
        name: l.name,
        boxes: l.kind === "remind" ? 0 : l.boxes,
        kind: l.kind,
        reason: l.reason ?? null,
        origEan: l.origEan ? normEan(l.origEan) : null,
        origName: l.origName ?? null,
        updatedBy: device.name,
        updatedAt: now,
      };
      await tx
        .insert(storeListLine)
        .values({ listId: s.id, ean: l.ean, ...v })
        .onConflictDoUpdate({ target: [storeListLine.listId, storeListLine.ean], set: v });
    }
    await tx.update(storeList).set({ updatedAt: now }).where(eq(storeList.id, s.id));
  });
  return currentList(db, store, now);
}

/** Scanner side: send this week's list to the purchasing office (it can still be changed until the deadline). */
export async function submitList(db: Db, device: { name: string; entityCode: number | null }, now = new Date()) {
  const store = needStore(device.entityCode);
  const rule = await getRule(db);
  const s = await ensureList(db, store, now, rule);
  const lines = await linesOf(db, [s.id]);
  if (!(lines.get(s.id) ?? []).some((l) => l.boxes > 0)) throw new BookingError("VALIDATION", "La lista è vuota");
  await db
    .update(storeList)
    .set({ status: "submitted", submittedAt: now, submittedBy: device.name, updatedAt: now })
    .where(eq(storeList.id, s.id));
  return currentList(db, store, now);
}

// ------------------------------------------------------------------ purchasing program (ERP API)

/**
 * The lists of one week (default: the week whose deadline is the latest one already passed, plus the
 * current one), with their lines.
 */
export async function erpLists(db: Db, week?: string | null, now = new Date()) {
  await pruneLists(db, now);
  const rule = await getRule(db);
  const cur = nextDeadline(now, rule);
  const weeks = week ? [week] : [cur.week, addDays(cur.week, -7)];
  const rows = await db.select().from(storeList).where(inArray(storeList.week, weeks)).orderBy(desc(storeList.week), storeList.entityCode);
  const lines = await linesOf(db, rows.map((r) => r.id));
  return { rule, current: cur.week, deadline: cur.deadline.toISOString(), lists: rows.map((r) => listView(r, lines.get(r.id) ?? [])) };
}

export async function markReceived(db: Db, id: number, now = new Date()) {
  const [s] = await db.update(storeList).set({ receivedAt: now }).where(eq(storeList.id, id)).returning();
  if (!s) throw new BookingError("NOT_FOUND", "Unknown list");
  return listView(s, (await linesOf(db, [id])).get(id) ?? []);
}

export const feedbackSchema = z.object({
  /** ean → boxes actually ordered (0 = not ordered) and a short note ("sostituito con …") */
  lines: z.record(z.string(), z.object({ boxes: z.number().min(0).max(9999), note: z.string().max(160).optional() })).default({}),
  note: z.string().max(400).optional(),
  deliveries: z.array(z.object({ supplier: z.string().max(80), when: z.string().max(40) })).max(30).optional(),
});

export async function setFeedback(db: Db, id: number, raw: unknown, now = new Date()) {
  const parsed = feedbackSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const [s] = await db
    .update(storeList)
    .set({ feedback: parsed.data, feedbackAt: now, status: "ordered" })
    .where(eq(storeList.id, id))
    .returning();
  if (!s) throw new BookingError("NOT_FOUND", "Unknown list");
  return listView(s, (await linesOf(db, [id])).get(id) ?? []);
}

/** Old lists are kept half a year. */
export async function pruneLists(db: Db, now = new Date()) {
  await db.delete(storeList).where(lt(storeList.week, addDays(localDate(now, TZ), -183)));
}
