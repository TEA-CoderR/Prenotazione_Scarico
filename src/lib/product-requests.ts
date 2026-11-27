// Product requests from the stores. On the scanner the store finds an item it cannot order (unknown
// barcode, removed from the Griglia, no wholesaler has it this week) and, instead of phoning, asks the
// purchasing office with one tap: why, how many boxes, a note and an optional photo. The office answers
// from the purchasing program and the scanners show the answer (and the next scan of the item shows it
// too). When the office lets the store order the item this week, the boxes go straight into the store's
// list. One open request per store and item: asking again updates it.
import { and, desc, eq, gte, inArray } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "@/db/client";
import { productRequest, storeListLine, type RequestMessage } from "@/db/schema";
import { BookingError } from "@/lib/booking/errors";
import { ensureList, getRule, normEan } from "@/lib/store-lists";

/** What the scanner said about the item. */
export const ITEM_KINDS = ["unknown", "off", "noquote"] as const;
/** Why the store wants it. */
export const REASONS = ["customers", "replaces", "new", "other"] as const;
/**
 * The office's answer: use = order another (Griglia) item instead · griglia = added to the Griglia ·
 * once = ordered this week only · direct = it is ordered from the direct supplier · no = we do not sell
 * it · info = the office needs more information (the store replies and the request is open again).
 */
export const ANSWER_KINDS = ["use", "griglia", "once", "direct", "no", "info"] as const;
const OPEN = ["open", "info"];
const PHOTO_MAX = 600_000; // bytes of JPEG (the app sends ~1000 px, ~150 KB)

const ean = z.string().trim().regex(/^\d{1,20}$/).transform(normEan);
const photo = z
  .string()
  .max(Math.ceil((PHOTO_MAX * 4) / 3) + 8)
  .transform((s, ctx) => {
    const b = Buffer.from(s, "base64");
    if (b.length < 100 || b[0] !== 0xff || b[1] !== 0xd8) {
      ctx.addIssue({ code: "custom", message: "photo must be a JPEG" });
      return z.NEVER;
    }
    return b;
  });

export const askSchema = z.object({
  ean,
  name: z.string().trim().max(120).default(""),
  kind: z.enum(ITEM_KINDS).default("unknown"),
  reason: z.enum(REASONS),
  boxes: z.number().int().min(1).max(999).nullable().optional(),
  note: z.string().trim().max(300).nullable().optional(),
  photo: photo.nullable().optional(),
});

type Row = typeof productRequest.$inferSelect;

/** The conversation: the stored messages, or (requests from before the messages column) what the old fields still say. */
export function threadOf(r: Row): RequestMessage[] {
  if (r.messages?.length) return r.messages;
  const out: RequestMessage[] = [];
  const at = r.createdAt.toISOString();
  const first = [r.note ?? "", r.reason !== "other" ? REASON_TEXT[r.reason] ?? "" : ""].filter(Boolean).join(" · ");
  out.push({ by: "store", text: first, at, ...(r.boxes ? { boxes: r.boxes } : {}) });
  if (r.storeReply) out.push({ by: "store", text: r.storeReply, at: r.updatedAt.toISOString() });
  if (r.answer) out.push({ by: "office", text: r.answer, at: (r.handledAt ?? r.updatedAt).toISOString(), kind: r.answerKind ?? undefined });
  return out;
}

const REASON_TEXT: Record<string, string> = {
  customers: "I clienti lo chiedono",
  replaces: "Sostituisce un articolo finito",
  new: "Novità del fornitore",
};

function dropLastAnswer(t: RequestMessage[], answer: string | null): RequestMessage[] {
  const i = t.map((x) => x.by).lastIndexOf("office");
  return i >= 0 && answer && t[i].text === answer ? [...t.slice(0, i), ...t.slice(i + 1)] : t;
}

const msg = (by: RequestMessage["by"], text: string, now: Date, extra: Partial<RequestMessage> = {}): RequestMessage =>
  ({ by, text, at: now.toISOString(), ...extra });

const view = (r: Row) => ({
  id: r.id,
  entityCode: r.entityCode,
  ean: r.ean,
  name: r.name,
  kind: r.kind,
  reason: r.reason,
  boxes: r.boxes,
  note: r.note,
  hasPhoto: r.photo != null,
  device: r.device,
  status: r.status,
  answerKind: r.answerKind,
  answer: r.answer,
  answerEan: r.answerEan,
  answerName: r.answerName,
  answerBoxes: r.answerBoxes,
  addedToList: r.addedListId != null,
  handledBy: r.handledBy,
  handledAt: r.handledAt?.toISOString() ?? null,
  storeReply: r.storeReply,
  seenAt: r.seenAt?.toISOString() ?? null,
  messages: threadOf(r),
  createdAt: r.createdAt.toISOString(),
  updatedAt: r.updatedAt.toISOString(),
});
export type RequestView = ReturnType<typeof view>;

function needStore(entityCode: number | null): number {
  if (entityCode == null) throw new BookingError("FORBIDDEN", "Questo scanner non è collegato a un negozio");
  return entityCode;
}

/** Scanner side: ask the office (replaces this store's open request of the same item). */
export async function askRequest(db: Db, device: { name: string; entityCode: number | null }, raw: unknown, now = new Date()) {
  const store = needStore(device.entityCode);
  const parsed = askSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", "Scegli il motivo della richiesta");
  const p = parsed.data;
  if (p.reason === "other" && !p.note) throw new BookingError("VALIDATION", "Scrivi in una riga perché serve");
  const v = {
    name: p.name,
    kind: p.kind,
    reason: p.reason,
    boxes: p.boxes ?? null,
    note: p.note ?? null,
    device: device.name,
    status: "open",
    updatedAt: now,
    ...(p.photo ? { photo: p.photo } : {}),
  };
  const m = msg("store", p.note ?? "", now, { ...(p.boxes ? { boxes: p.boxes } : {}), ...(p.photo ? { photo: true } : {}) });
  return db.transaction(async (tx) => {
    const [open] = await tx
      .select()
      .from(productRequest)
      .where(and(eq(productRequest.entityCode, store), eq(productRequest.ean, p.ean), inArray(productRequest.status, OPEN)));
    const [r] = open
      ? await tx.update(productRequest).set({ ...v, messages: [...threadOf(open), m] }).where(eq(productRequest.id, open.id)).returning()
      : await tx.insert(productRequest).values({ entityCode: store, ean: p.ean, ...v, messages: [m], createdAt: now }).returning();
    return view(r);
  });
}

/** A store's requests of the last `days` days (newest first), without the photos. */
export async function storeRequests(db: Db, entityCode: number | null, now = new Date(), days = 90) {
  if (entityCode == null) return [];
  const rows = await db
    .select()
    .from(productRequest)
    .where(and(eq(productRequest.entityCode, entityCode), gte(productRequest.createdAt, new Date(now.getTime() - days * 86_400_000))))
    .orderBy(desc(productRequest.updatedAt))
    .limit(500);
  return rows.filter((r) => r.status !== "cancelled").map(view);
}

export const storeActionSchema = z.object({
  action: z.enum(["reply", "seen", "cancel"]),
  note: z.string().trim().max(300).optional(),
  photo: photo.nullable().optional(),
});

/** reply (to "send more information") · seen (the store read the answer) · cancel (no longer needed). */
export async function storeAction(db: Db, device: { name: string; entityCode: number | null }, id: number, raw: unknown, now = new Date()) {
  const store = needStore(device.entityCode);
  const parsed = storeActionSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const { action, note, photo: pic } = parsed.data;
  const [r] = await db
    .select()
    .from(productRequest)
    .where(and(eq(productRequest.id, id), eq(productRequest.entityCode, store)));
  if (!r) throw new BookingError("NOT_FOUND", "Richiesta non trovata");
  let set: Partial<typeof productRequest.$inferInsert>;
  if (action === "seen") set = { seenAt: now };
  else if (action === "cancel") {
    if (!OPEN.includes(r.status)) throw new BookingError("CONFLICT", "L'ufficio ha già risposto");
    set = { status: "cancelled", updatedAt: now };
  } else {
    if (r.status !== "info") throw new BookingError("CONFLICT", "L'ufficio non ha chiesto altre informazioni");
    if (!note && !pic) throw new BookingError("VALIDATION", "Scrivi la risposta o fai una foto");
    set = {
      status: "open",
      storeReply: note ?? null,
      updatedAt: now,
      messages: [...threadOf(r), msg("store", note ?? "", now, pic ? { photo: true } : {})],
      ...(pic ? { photo: pic } : {}),
    };
  }
  const [u] = await db.update(productRequest).set(set).where(eq(productRequest.id, id)).returning();
  return view(u);
}

// ------------------------------------------------------------------ purchasing program

export async function erpRequests(db: Db, status?: string | null, now = new Date(), days = 120) {
  const conds = [gte(productRequest.updatedAt, new Date(now.getTime() - days * 86_400_000))];
  if (status && status !== "all") conds.push(inArray(productRequest.status, status.split(",")));
  const rows = await db.select().from(productRequest).where(and(...conds)).orderBy(desc(productRequest.createdAt)).limit(3000);
  return rows.map(view);
}

export async function requestPhoto(db: Db, id: number): Promise<Buffer | null> {
  const [r] = await db.select({ photo: productRequest.photo }).from(productRequest).where(eq(productRequest.id, id));
  return r?.photo ?? null;
}

export const answerSchema = z.object({
  action: z.enum(["answer", "reopen"]),
  kind: z.enum(ANSWER_KINDS).optional(),
  answer: z.string().trim().min(1).max(300).optional(),
  ean: z.string().trim().regex(/^\d{1,20}$/).transform(normEan).optional(),
  name: z.string().trim().max(120).optional(),
  /** Boxes to put in the store's list this week (use / griglia / once). */
  boxes: z.number().int().min(1).max(999).optional(),
  by: z.string().trim().max(80).optional(),
});

const LIST_KINDS = new Set(["use", "griglia", "once"]);

/**
 * The office answers (the store sees `answer`, written by the program in Italian). With `boxes` the item
 * (for "use": the item to order instead) goes into the store's list of this week. reopen = undo: the
 * answer is removed and the list line is put back as it was.
 */
export async function answerRequest(db: Db, id: number, raw: unknown, now = new Date()) {
  const parsed = answerSchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const a = parsed.data;
  return db.transaction(async (tx) => {
    const [r] = await tx.select().from(productRequest).where(eq(productRequest.id, id));
    if (!r) throw new BookingError("NOT_FOUND", "Unknown request");
    if (a.action === "reopen") {
      if (r.addedListId != null) {
        const lineEan = r.answerKind === "use" && r.answerEan ? r.answerEan : r.ean;
        const where = and(eq(storeListLine.listId, r.addedListId), eq(storeListLine.ean, lineEan));
        if (r.addedBefore == null) await tx.delete(storeListLine).where(where);
        else await tx.update(storeListLine).set({ boxes: r.addedBefore, updatedAt: now }).where(where);
      }
      const [u] = await tx
        .update(productRequest)
        .set({
          status: "open",
          answerKind: null,
          answer: null,
          answerEan: null,
          answerName: null,
          answerBoxes: null,
          addedListId: null,
          addedBefore: null,
          handledBy: null,
          handledAt: null,
          seenAt: null,
          updatedAt: now,
          // the answer is taken back: its message goes too
          messages: dropLastAnswer(threadOf(r), r.answer),
        })
        .where(eq(productRequest.id, id))
        .returning();
      return view(u);
    }
    if (!a.kind || !a.answer) throw new BookingError("VALIDATION", "An answer needs kind and answer");
    if (!OPEN.includes(r.status)) throw new BookingError("CONFLICT", "Already answered or cancelled");
    if (a.kind === "use" && !a.ean) throw new BookingError("VALIDATION", "'use' needs the barcode of the item to order");
    if (a.boxes && !LIST_KINDS.has(a.kind)) throw new BookingError("VALIDATION", "Only use / griglia / once put boxes in the list");
    let addedListId: number | null = null;
    let addedBefore: number | null = null;
    if (a.boxes) {
      const s = await ensureList(tx as unknown as Db, r.entityCode, now, await getRule(tx as unknown as Db));
      const lineEan = a.kind === "use" ? a.ean! : r.ean;
      const [old] = await tx
        .select()
        .from(storeListLine)
        .where(and(eq(storeListLine.listId, s.id), eq(storeListLine.ean, lineEan)));
      addedListId = s.id;
      addedBefore = old ? old.boxes : null;
      const v = {
        name: a.kind === "use" ? (a.name ?? "") : r.name || a.name || "",
        boxes: Math.max(old?.boxes ?? 0, a.boxes),
        kind: a.kind === "once" ? "other" : "griglia",
        reason: old?.reason ?? "Approvato dall'ufficio acquisti",
        origEan: a.kind === "use" ? r.ean : null,
        origName: a.kind === "use" ? r.name || null : null,
        updatedBy: a.by ? `Ufficio · ${a.by}` : "Ufficio acquisti",
        updatedAt: now,
      };
      await tx
        .insert(storeListLine)
        .values({ listId: s.id, ean: lineEan, ...v })
        .onConflictDoUpdate({ target: [storeListLine.listId, storeListLine.ean], set: v });
    }
    const [u] = await tx
      .update(productRequest)
      .set({
        status: a.kind === "info" ? "info" : "answered",
        answerKind: a.kind,
        answer: a.answer,
        answerEan: a.ean ?? null,
        answerName: a.name ?? null,
        answerBoxes: a.boxes ?? null,
        addedListId,
        addedBefore,
        handledBy: a.by ?? null,
        handledAt: now,
        seenAt: null,
        storeReply: a.kind === "info" ? null : r.storeReply,
        updatedAt: now,
        messages: [...threadOf(r), msg("office", a.answer, now, { kind: a.kind, ...(a.boxes ? { boxes: a.boxes } : {}) })],
      })
      .where(eq(productRequest.id, id))
      .returning();
    return view(u);
  });
}
