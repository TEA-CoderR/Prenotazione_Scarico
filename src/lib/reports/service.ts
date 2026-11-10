// Reports over a date range: volumes, punctuality, no-shows, unloading times, waiting,
// confirmation speed, carriers and recipients, plus a CSV export for Excel.
// Times come from booking_event, so every figure is what the warehouse actually recorded.
import { sql } from "drizzle-orm";
import { z } from "zod";
import type { Db } from "@/db/client";
import type { BookingStatus, VehicleType } from "@/db/schema";
import type { SessionUser } from "@/lib/auth/site-access";
import { BookingError } from "@/lib/booking/errors";
import { addDays, isoWeekday, localDate, localTime, zonedToUtc } from "@/lib/time";

const TZ = "Europe/Rome";
const MAX_DAYS = 366;

export const reportQuerySchema = z
  .object({
    from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
    to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
    siteId: z.number().int().nullable(), // null = every site the user may see
  })
  .refine((q) => q.from <= q.to, "from must not be after to")
  .refine((q) => (Date.parse(q.to) - Date.parse(q.from)) / 86_400_000 < MAX_DAYS, `At most ${MAX_DAYS} days`);

export type ReportQuery = z.infer<typeof reportQuerySchema>;

export interface ReportRow {
  id: number;
  code: string;
  siteId: number;
  siteName: string;
  entityCode: number;
  entityName: string;
  carrierName: string;
  contactEmail: string;
  driverName: string | null;
  driverPhone: string | null;
  plate: string | null;
  vehicleType: VehicleType;
  pallets: number;
  parcels: number;
  supplier: string | null;
  orderNo: string | null;
  ddtNo: string | null;
  notes: string | null;
  status: BookingStatus;
  startAt: Date;
  endAt: Date;
  createdAt: Date;
  confirmedAt: Date | null;
  arrivedAt: Date | null;
  unloadingAt: Date | null;
  completedAt: Date | null;
  cancelledByCarrier: boolean;
  lateToleranceMin: number;
  confirmSlaHours: number;
}

/** Sites the user may report on; throws for clerks and foreign sites. */
function allowedSites(user: SessionUser, siteId: number | null): number[] | "all" {
  if (user.role === "warehouse_clerk") throw new BookingError("FORBIDDEN", "Managers and admins only");
  if (user.role === "admin") return siteId === null ? "all" : [siteId];
  if (siteId === null) return user.siteIds;
  if (!user.siteIds.includes(siteId)) throw new BookingError("FORBIDDEN", "Only your own warehouses");
  return [siteId];
}

export async function loadReportRows(db: Db, user: SessionUser, raw: z.input<typeof reportQuerySchema>): Promise<ReportRow[]> {
  const parsed = reportQuerySchema.safeParse(raw);
  if (!parsed.success) throw new BookingError("VALIDATION", z.prettifyError(parsed.error));
  const q = parsed.data;
  const sites = allowedSites(user, q.siteId);
  if (sites !== "all" && sites.length === 0) return [];

  const start = zonedToUtc(q.from, "00:00", TZ);
  const end = zonedToUtc(addDays(q.to, 1), "00:00", TZ);
  const siteFilter = sites === "all" ? sql`true` : sql`b.site_id in (${sql.join(sites.map((id) => sql`${id}`), sql`, `)})`;

  const res = await db.execute(sql`
    select b.id, b.code, b.site_id, s.name as site_name, e.code as entity_code, e.name as entity_name,
           b.carrier_name, b.contact_email, b.driver_name, b.driver_phone, b.plate, b.vehicle_type,
           b.pallets, b.parcels, b.supplier, b.order_no, b.ddt_no, b.notes, b.status,
           b.start_at, b.end_at, b.created_at,
           ev.confirmed_at, ev.arrived_at, ev.unloading_at, ev.completed_at,
           coalesce(ev.cancelled_by_carrier, false) as cancelled_by_carrier,
           coalesce(p.late_tolerance_min, 15) as late_tolerance_min,
           coalesce(p.confirm_sla_hours, 4) as confirm_sla_hours
    from booking b
    join site s on s.id = b.site_id
    join entity e on e.id = b.entity_id
    left join booking_policy p on p.site_id = b.site_id
    left join lateral (
      select min(created_at) filter (where to_status = 'confirmed') as confirmed_at,
             min(created_at) filter (where to_status = 'arrived')   as arrived_at,
             min(created_at) filter (where to_status = 'unloading') as unloading_at,
             min(created_at) filter (where to_status = 'completed') as completed_at,
             bool_or(to_status = 'cancelled' and actor_type = 'carrier') as cancelled_by_carrier
      from booking_event where booking_id = b.id
    ) ev on true
    where b.start_at >= ${start} and b.start_at < ${end} and ${siteFilter}
    order by b.start_at`);

  const d = (v: unknown) => (v == null ? null : new Date(v as string));
  return res.rows.map((r) => ({
    id: Number(r.id),
    code: r.code as string,
    siteId: Number(r.site_id),
    siteName: r.site_name as string,
    entityCode: Number(r.entity_code),
    entityName: r.entity_name as string,
    carrierName: r.carrier_name as string,
    contactEmail: r.contact_email as string,
    driverName: r.driver_name as string | null,
    driverPhone: r.driver_phone as string | null,
    plate: r.plate as string | null,
    vehicleType: r.vehicle_type as VehicleType,
    pallets: Number(r.pallets),
    parcels: Number(r.parcels),
    supplier: r.supplier as string | null,
    orderNo: r.order_no as string | null,
    ddtNo: r.ddt_no as string | null,
    notes: r.notes as string | null,
    status: r.status as BookingStatus,
    startAt: d(r.start_at)!,
    endAt: d(r.end_at)!,
    createdAt: d(r.created_at)!,
    confirmedAt: d(r.confirmed_at),
    arrivedAt: d(r.arrived_at),
    unloadingAt: d(r.unloading_at),
    completedAt: d(r.completed_at),
    cancelledByCarrier: Boolean(r.cancelled_by_carrier),
    lateToleranceMin: Number(r.late_tolerance_min),
    confirmSlaHours: Number(r.confirm_sla_hours),
  }));
}

// ------------------------------------------------------------------ computation

const minutes = (a: Date, b: Date) => (b.getTime() - a.getTime()) / 60_000;
const avg = (xs: number[]) => (xs.length ? xs.reduce((s, x) => s + x, 0) / xs.length : null);
const round = (x: number | null, digits = 0) => (x === null ? null : Math.round(x * 10 ** digits) / 10 ** digits);
const rate = (part: number, whole: number) => (whole ? part / whole : null);

export function quantile(xs: number[], q: number): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const pos = (s.length - 1) * q;
  const lo = Math.floor(pos);
  const hi = Math.ceil(pos);
  return s[lo] + (s[hi] - s[lo]) * (pos - lo);
}

/** Bookings that took a slot (not declined, not cancelled). */
const tookSlot = (r: ReportRow) => r.status !== "rejected" && r.status !== "cancelled";

export interface Report {
  from: string;
  to: string;
  totals: {
    requests: number;
    byStatus: Record<BookingStatus, number>;
    cancelledByCarrier: number;
    pallets: number;
  };
  punctuality: {
    arrived: number;
    onTime: number;
    late: number;
    onTimeRate: number | null;
    avgLateMin: number | null; // among late arrivals
    noShows: number;
    noShowRate: number | null; // no-shows / (arrived + no-shows)
  };
  confirmation: {
    decided: number;
    avgHours: number | null;
    withinSlaRate: number | null;
  };
  waiting: { measured: number; avgMin: number | null; over15Rate: number | null };
  unloading: {
    vehicleType: VehicleType;
    measured: number;
    plannedMin: number | null;
    avgMin: number | null;
    medianMin: number | null;
    p90Min: number | null;
  }[];
  carriers: {
    name: string;
    requests: number;
    completed: number;
    noShows: number;
    noShowRate: number | null;
    onTimeRate: number | null;
    cancelledByCarrier: number;
  }[];
  entities: { code: number; name: string; bookings: number; pallets: number }[];
  daily: { date: string; bookings: number; pallets: number }[];
  /** Bookings starting in each hour, per ISO weekday (index 0 = Monday). */
  heatmap: { weekday: number; hour: number; bookings: number }[];
}

export function computeReport(rows: ReportRow[], from: string, to: string): Report {
  const statuses: BookingStatus[] = ["requested", "confirmed", "rejected", "cancelled", "arrived", "unloading", "completed", "no_show"];
  const byStatus = Object.fromEntries(statuses.map((s) => [s, 0])) as Record<BookingStatus, number>;
  for (const r of rows) byStatus[r.status]++;
  const slotRows = rows.filter(tookSlot);

  // Punctuality: arrival time vs slot start; early counts as on time.
  const arrivals = rows.filter((r) => r.arrivedAt).map((r) => ({ r, delay: minutes(r.startAt, r.arrivedAt!) }));
  const late = arrivals.filter((a) => a.delay > a.r.lateToleranceMin);
  const noShows = byStatus.no_show;

  const decided = rows.filter((r) => r.confirmedAt);
  const confirmHours = decided.map((r) => minutes(r.createdAt, r.confirmedAt!) / 60);

  // Waiting at the dock: from when the driver could start (arrival or slot start, whichever is later).
  const waits = rows
    .filter((r) => r.arrivedAt && r.unloadingAt)
    .map((r) => minutes(new Date(Math.max(r.arrivedAt!.getTime(), r.startAt.getTime())), r.unloadingAt!))
    .map((m) => Math.max(0, m));

  const vehicleTypes: VehicleType[] = ["parcel", "van", "truck", "semi"];
  const unloading = vehicleTypes.map((v) => {
    const done = rows.filter((r) => r.vehicleType === v && r.unloadingAt && r.completedAt);
    const actual = done.map((r) => minutes(r.unloadingAt!, r.completedAt!));
    const planned = done.map((r) => minutes(r.startAt, r.endAt));
    return {
      vehicleType: v,
      measured: done.length,
      plannedMin: round(avg(planned)),
      avgMin: round(avg(actual)),
      medianMin: round(quantile(actual, 0.5)),
      p90Min: round(quantile(actual, 0.9)),
    };
  });

  // Carriers grouped case/space-insensitively; shown with their most frequent spelling.
  const groups = new Map<string, ReportRow[]>();
  for (const r of rows) {
    const key = r.carrierName.trim().replace(/\s+/g, " ").toLowerCase();
    groups.set(key, [...(groups.get(key) ?? []), r]);
  }
  const carriers = [...groups.values()]
    .map((list) => {
      const names = new Map<string, number>();
      for (const r of list) names.set(r.carrierName.trim(), (names.get(r.carrierName.trim()) ?? 0) + 1);
      const name = [...names.entries()].sort((a, b) => b[1] - a[1])[0][0];
      const arrivedL = list.filter((r) => r.arrivedAt);
      const noShowL = list.filter((r) => r.status === "no_show").length;
      return {
        name,
        requests: list.length,
        completed: list.filter((r) => r.status === "completed").length,
        noShows: noShowL,
        noShowRate: rate(noShowL, arrivedL.length + noShowL),
        onTimeRate: rate(arrivedL.filter((r) => minutes(r.startAt, r.arrivedAt!) <= r.lateToleranceMin).length, arrivedL.length),
        cancelledByCarrier: list.filter((r) => r.cancelledByCarrier).length,
      };
    })
    .sort((a, b) => b.requests - a.requests || a.name.localeCompare(b.name));

  const entityMap = new Map<number, { code: number; name: string; bookings: number; pallets: number }>();
  for (const r of slotRows) {
    const e = entityMap.get(r.entityCode) ?? { code: r.entityCode, name: r.entityName, bookings: 0, pallets: 0 };
    e.bookings++;
    e.pallets += r.pallets;
    entityMap.set(r.entityCode, e);
  }

  const daily: Report["daily"] = [];
  for (let d = from; d <= to; d = addDays(d, 1)) daily.push({ date: d, bookings: 0, pallets: 0 });
  const dayIndex = new Map(daily.map((x, i) => [x.date, i]));
  const heat = new Map<string, number>();
  for (const r of slotRows) {
    const date = localDate(r.startAt, TZ);
    const i = dayIndex.get(date);
    if (i !== undefined) {
      daily[i].bookings++;
      daily[i].pallets += r.pallets;
    }
    const key = `${isoWeekday(date)}:${Number(localTime(r.startAt, TZ).slice(0, 2))}`;
    heat.set(key, (heat.get(key) ?? 0) + 1);
  }

  return {
    from,
    to,
    totals: {
      requests: rows.length,
      byStatus,
      cancelledByCarrier: rows.filter((r) => r.cancelledByCarrier).length,
      pallets: slotRows.reduce((s, r) => s + r.pallets, 0),
    },
    punctuality: {
      arrived: arrivals.length,
      onTime: arrivals.length - late.length,
      late: late.length,
      onTimeRate: rate(arrivals.length - late.length, arrivals.length),
      avgLateMin: round(avg(late.map((a) => a.delay))),
      noShows,
      noShowRate: rate(noShows, arrivals.length + noShows),
    },
    confirmation: {
      decided: decided.length,
      avgHours: round(avg(confirmHours), 1),
      withinSlaRate: rate(decided.filter((r, i) => confirmHours[i] <= r.confirmSlaHours).length, decided.length),
    },
    waiting: { measured: waits.length, avgMin: round(avg(waits)), over15Rate: rate(waits.filter((w) => w > 15).length, waits.length) },
    unloading,
    carriers,
    entities: [...entityMap.values()].sort((a, b) => a.code - b.code),
    daily,
    heatmap: [...heat.entries()].map(([k, n]) => {
      const [w, h] = k.split(":").map(Number);
      return { weekday: w, hour: h, bookings: n };
    }),
  };
}

export async function getReport(db: Db, user: SessionUser, raw: z.input<typeof reportQuerySchema>): Promise<Report> {
  const rows = await loadReportRows(db, user, raw);
  return computeReport(rows, raw.from, raw.to);
}

// ------------------------------------------------------------------ CSV export

const STATUS_IT: Record<BookingStatus, string> = {
  requested: "In attesa di conferma",
  confirmed: "Confermata",
  rejected: "Non accettata",
  cancelled: "Annullata",
  arrived: "Arrivato",
  unloading: "In scarico",
  completed: "Completata",
  no_show: "Non presentato",
};
const VEHICLE_IT: Record<VehicleType, string> = { parcel: "Corriere / colli", van: "Furgone", truck: "Motrice", semi: "Bilico" };

function csvCell(v: string | number | null): string {
  if (v === null) return "";
  const s = String(v);
  // Neutralise spreadsheet formulas in user-typed text (CSV injection).
  const safe = /^[=+\-@\t\r]/.test(s) && typeof v === "string" ? `'${s}` : s;
  return /[";\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

/**
 * Semicolon-separated, UTF-8 with BOM: opens correctly in Excel with Italian settings.
 * Contains personal data (driver, phone, plate): for internal use only.
 */
export function bookingsCsv(rows: ReportRow[]): string {
  const when = (x: Date | null) => (x ? `${localDate(x, TZ)} ${localTime(x, TZ)}` : null);
  const header = [
    "Codice", "Data", "Ora", "Fine", "Magazzino", "Destinatario", "Stato", "Trasportatore", "E-mail",
    "Autista", "Telefono", "Targa", "Mezzo", "Pallet", "Colli", "Fornitore", "Ordine", "DDT", "Note",
    "Richiesta il", "Confermata il", "Arrivo", "Inizio scarico", "Fine scarico", "Ritardo arrivo (min)", "Durata scarico (min)",
  ];
  const lines = rows.map((r) =>
    [
      r.code,
      localDate(r.startAt, TZ),
      localTime(r.startAt, TZ),
      localTime(r.endAt, TZ),
      r.siteName,
      `${r.entityCode} ${r.entityName}`,
      STATUS_IT[r.status],
      r.carrierName,
      r.contactEmail,
      r.driverName,
      r.driverPhone,
      r.plate,
      VEHICLE_IT[r.vehicleType],
      r.pallets,
      r.parcels,
      r.supplier,
      r.orderNo,
      r.ddtNo,
      r.notes,
      when(r.createdAt),
      when(r.confirmedAt),
      when(r.arrivedAt),
      when(r.unloadingAt),
      when(r.completedAt),
      r.arrivedAt ? Math.round(minutes(r.startAt, r.arrivedAt)) : null,
      r.unloadingAt && r.completedAt ? Math.round(minutes(r.unloadingAt, r.completedAt)) : null,
    ]
      .map(csvCell)
      .join(";"),
  );
  return "﻿" + [header.join(";"), ...lines].join("\r\n") + "\r\n";
}
