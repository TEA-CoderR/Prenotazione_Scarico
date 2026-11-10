"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import type { Report } from "@/lib/reports/service";
import { formatDay, messages } from "@/lib/i18n/messages";
import { buttonClass, inputClass } from "../ui";
import { Notice, type MeSite } from "./admin-console";
import { api, errorText } from "./api";

const t = messages.it;
// Sequential blue ramp (steps 150 → 650) for the heatmap; 0 bookings = empty cell.
const BLUES = ["#b7d3f6", "#9ec5f4", "#86b6ef", "#6da7ec", "#5598e7", "#3987e5", "#2a78d6", "#256abf", "#1c5cab", "#184f95", "#104281"];
const WEEKDAY_SHORT = ["Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom"];

function romeToday() {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome" }).format(new Date());
}
function shift(ymd: string, days: number) {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}
const pct = (x: number | null) => (x === null ? "—" : `${Math.round(x * 100)}%`);
const num = (x: number | null, unit = "") => (x === null ? "—" : `${x.toLocaleString("it-IT")}${unit}`);

type Preset = "7" | "30" | "90" | "month";

export function ReportTab({ sites, isAdmin }: { sites: MeSite[]; isAdmin: boolean }) {
  const [today] = useState(romeToday);
  const [from, setFrom] = useState(() => shift(today, -29));
  const [to, setTo] = useState(today);
  const [site, setSite] = useState("all");
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const query = useMemo(() => new URLSearchParams({ from, to, site }).toString(), [from, to, site]);

  useEffect(() => {
    let live = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- shows the spinner for this request
    setLoading(true);
    api<Report>("GET", `/api/admin/reports?${query}`).then((r) => {
      if (!live) return;
      setLoading(false);
      if (r.ok) {
        setReport(r.data);
        setError(null);
      } else setError(errorText(r));
    });
    return () => {
      live = false;
    };
  }, [query]);

  function preset(p: Preset) {
    if (p === "month") {
      const firstThis = `${today.slice(0, 7)}-01`;
      const lastPrev = shift(firstThis, -1);
      setFrom(`${lastPrev.slice(0, 7)}-01`);
      setTo(lastPrev);
    } else {
      setFrom(shift(today, -(Number(p) - 1)));
      setTo(today);
    }
  }

  const r = report;
  return (
    <div className="flex flex-col gap-6">
      {/* Filters: one row above the charts */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-wrap gap-1" role="group" aria-label="Periodo">
          {(
            [
              ["7", "7 giorni"],
              ["30", "30 giorni"],
              ["90", "90 giorni"],
              ["month", "Mese scorso"],
            ] as [Preset, string][]
          ).map(([p, label]) => (
            <button key={p} onClick={() => preset(p)} className="rounded-full border border-slate-300 px-3 py-1.5 text-sm hover:border-blue-700 dark:border-slate-700">
              {label}
            </button>
          ))}
        </div>
        <label className="flex flex-col text-sm">
          Dal
          <input type="date" value={from} max={to} onChange={(e) => e.target.value && setFrom(e.target.value)} className={`${inputClass} py-1.5`} />
        </label>
        <label className="flex flex-col text-sm">
          Al
          <input type="date" value={to} min={from} onChange={(e) => e.target.value && setTo(e.target.value)} className={`${inputClass} py-1.5`} />
        </label>
        {sites.length > 1 && (
          <label className="flex flex-col text-sm">
            Magazzino
            <select value={site} onChange={(e) => setSite(e.target.value)} className={`${inputClass} py-1.5`}>
              <option value="all">{isAdmin ? "Tutti i magazzini" : "Tutti i miei magazzini"}</option>
              {sites.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
        )}
        <a href={`/api/admin/reports/export?${query}`} download className={buttonClass("secondary", "ml-auto min-h-10 text-sm")}>
          Scarica per Excel (CSV)
        </a>
      </div>

      {error && <Notice kind="error">{error}</Notice>}
      {!r && !error && <p className="text-slate-500">Caricamento…</p>}

      {r && (
        <div className={`flex flex-col gap-8 transition-opacity ${loading ? "opacity-60" : ""}`} aria-busy={loading}>
          {r.totals.requests === 0 && <Notice kind="warn">Nessuna prenotazione nel periodo scelto.</Notice>}

          <section className="grid grid-cols-2 gap-3 lg:grid-cols-3">
            <Stat label="Richieste" value={num(r.totals.requests)} sub={`${r.totals.byStatus.completed} completate · ${num(r.totals.pallets)} pallet`} />
            <Stat
              label="Puntualità"
              value={pct(r.punctuality.onTimeRate)}
              sub={r.punctuality.arrived ? `${r.punctuality.late} in ritardo su ${r.punctuality.arrived} arrivi${r.punctuality.avgLateMin ? ` · in media ${r.punctuality.avgLateMin} min` : ""}` : "nessun arrivo registrato"}
            />
            <Stat
              label="Non presentati"
              value={pct(r.punctuality.noShowRate)}
              sub={`${r.punctuality.noShows} su ${r.punctuality.arrived + r.punctuality.noShows} attesi`}
            />
            <Stat
              label="Tempo di conferma"
              value={r.confirmation.avgHours === null ? "—" : `${r.confirmation.avgHours.toLocaleString("it-IT")} h`}
              sub={r.confirmation.decided ? `${pct(r.confirmation.withinSlaRate)} entro l'obiettivo` : "nessuna conferma"}
            />
            <Stat
              label="Attesa in baia"
              value={num(r.waiting.avgMin, " min")}
              sub={r.waiting.measured ? `${pct(r.waiting.over15Rate)} oltre 15 min` : "nessuno scarico registrato"}
            />
            <Stat
              label="Annullate dal trasportatore"
              value={num(r.totals.cancelledByCarrier)}
              sub={`${r.totals.byStatus.rejected} non accettate dal magazzino`}
            />
          </section>

          <section className="flex flex-col gap-2">
            <h2 className="text-lg font-semibold">Scarichi prenotati al giorno</h2>
            <DailyBars daily={r.daily} />
            <DataTable
              summary="Vedi i dati giorno per giorno"
              head={["Giorno", "Scarichi", "Pallet"]}
              rows={r.daily.filter((d) => d.bookings).map((d) => [formatDay(d.date, "it", { weekday: "short", day: "numeric", month: "short" }), d.bookings, d.pallets])}
            />
          </section>

          <section className="flex flex-col gap-2">
            <h2 className="text-lg font-semibold">Quando arrivano i mezzi</h2>
            <p className="text-sm text-slate-500">Scarichi per giorno della settimana e ora di inizio: le celle più scure sono le ore più piene.</p>
            <Heatmap cells={r.heatmap} />
          </section>

          <section className="flex flex-col gap-2">
            <h2 className="text-lg font-semibold">Durata reale dello scarico</h2>
            <p className="text-sm text-slate-500">Dall&apos;inizio alla fine dello scarico registrati in magazzino, confrontati con la durata prenotata.</p>
            <UnloadingTable rows={r.unloading} />
          </section>

          <section className="flex flex-col gap-2">
            <h2 className="text-lg font-semibold">Trasportatori</h2>
            <CarriersTable rows={r.carriers} />
          </section>

          <section className="flex flex-col gap-2">
            <h2 className="text-lg font-semibold">Destinatari</h2>
            <SimpleTable
              head={["Destinatario", "Scarichi", "Pallet"]}
              rows={r.entities.map((e) => [`${e.code} · ${e.name}`, e.bookings, e.pallets])}
              empty="Nessuno scarico nel periodo."
            />
          </section>
        </div>
      )}
    </div>
  );
}

function Stat({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div className="rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
      <div className="text-sm text-slate-500">{label}</div>
      <div className="mt-1 text-3xl font-semibold tabular-nums">{value}</div>
      <div className="mt-1 text-sm text-slate-600 dark:text-slate-400">{sub}</div>
    </div>
  );
}

/** Single-series bar chart over time, with a hover tooltip per bar. */
function DailyBars({ daily }: { daily: Report["daily"] }) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 720;
  const H = 200;
  const pad = { l: 32, r: 8, t: 12, b: 24 };
  const max = Math.max(1, ...daily.map((d) => d.bookings));
  const step = max <= 4 ? 1 : Math.ceil(max / 4);
  const ticks = Array.from({ length: Math.floor(max / step) + 1 }, (_, i) => i * step);
  const top = ticks.at(-1)! < max ? ticks.at(-1)! + step : ticks.at(-1)!;
  if (ticks.at(-1)! < top) ticks.push(top);
  const plotW = W - pad.l - pad.r;
  const plotH = H - pad.t - pad.b;
  const slot = plotW / daily.length;
  const barW = Math.max(1, slot - 2); // 2px surface gap between bars
  const y = (v: number) => pad.t + plotH - (v / top) * plotH;
  const labelEvery = Math.ceil(daily.length / 8);
  const h = hover !== null ? daily[hover] : null;

  return (
    <div className="relative">
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img" aria-label="Scarichi prenotati al giorno nel periodo" onMouseLeave={() => setHover(null)}>
        {ticks.map((v) => (
          <g key={v}>
            <line x1={pad.l} x2={W - pad.r} y1={y(v)} y2={y(v)} stroke={v === 0 ? "var(--viz-axis)" : "var(--viz-grid)"} strokeWidth="1" />
            <text x={pad.l - 6} y={y(v) + 4} textAnchor="end" fontSize="11" fill="var(--viz-muted)">
              {v}
            </text>
          </g>
        ))}
        {daily.map((d, i) => {
          const x = pad.l + i * slot + 1;
          const bh = (d.bookings / top) * plotH;
          const r = Math.min(4, barW / 2, bh);
          const base = pad.t + plotH;
          return (
            <g key={d.date}>
              {/* invisible hit target taller than the bar */}
              <rect x={pad.l + i * slot} y={pad.t} width={slot} height={plotH} fill="transparent" onMouseEnter={() => setHover(i)} />
              {d.bookings > 0 && (
                <path
                  d={`M${x},${base} V${base - bh + r} Q${x},${base - bh} ${x + r},${base - bh} H${x + barW - r} Q${x + barW},${base - bh} ${x + barW},${base - bh + r} V${base} Z`}
                  fill="var(--viz-series-1)"
                  opacity={hover === null || hover === i ? 1 : 0.55}
                  pointerEvents="none"
                />
              )}
              {i % labelEvery === 0 && (
                <text x={x + barW / 2} y={H - 6} textAnchor="middle" fontSize="11" fill="var(--viz-muted)">
                  {formatDay(d.date, "it", { day: "numeric", month: "short" })}
                </text>
              )}
            </g>
          );
        })}
      </svg>
      {h && (
        <div
          className="pointer-events-none absolute top-0 rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm shadow-sm dark:border-slate-700 dark:bg-slate-900"
          style={{ left: `clamp(0px, calc(${((pad.l + hover! * slot) / W) * 100}% - 60px), calc(100% - 140px))` }}
        >
          <div className="font-medium first-letter:uppercase">{formatDay(h.date, "it", { weekday: "long", day: "numeric", month: "long" })}</div>
          <div className="tabular-nums">
            {h.bookings} scarichi · {h.pallets} pallet
          </div>
        </div>
      )}
    </div>
  );
}

/** Weekday × hour heatmap on a single-hue sequential ramp. */
function Heatmap({ cells }: { cells: Report["heatmap"] }) {
  const [hover, setHover] = useState<{ w: number; h: number; n: number } | null>(null);
  if (!cells.length) return <p className="text-sm text-slate-500">Nessuno scarico nel periodo.</p>;
  const hours = cells.map((c) => c.hour);
  const minH = Math.min(8, ...hours);
  const maxH = Math.max(17, ...hours);
  const days = cells.some((c) => c.weekday >= 6) ? [1, 2, 3, 4, 5, 6, 7] : [1, 2, 3, 4, 5];
  const max = Math.max(...cells.map((c) => c.bookings));
  const value = (w: number, h: number) => cells.find((c) => c.weekday === w && c.hour === h)?.bookings ?? 0;
  const color = (n: number) => (n === 0 ? "var(--viz-empty)" : BLUES[Math.min(BLUES.length - 1, Math.round((n / max) * (BLUES.length - 1)))]);
  const hourList = Array.from({ length: maxH - minH + 1 }, (_, i) => minH + i);

  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-x-auto">
        <table className="border-separate" style={{ borderSpacing: 2 }} aria-label="Scarichi per giorno della settimana e ora">
          <thead>
            <tr>
              <th />
              {hourList.map((h) => (
                <th key={h} scope="col" className="px-0.5 text-center text-xs font-normal text-slate-500 tabular-nums">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {days.map((w) => (
              <tr key={w}>
                <th scope="row" className="pr-2 text-left text-sm font-normal text-slate-600 dark:text-slate-400">
                  {WEEKDAY_SHORT[w - 1]}
                </th>
                {hourList.map((h) => {
                  const n = value(w, h);
                  return (
                    <td
                      key={h}
                      onMouseEnter={() => setHover({ w, h, n })}
                      onMouseLeave={() => setHover(null)}
                      title={`${WEEKDAY_SHORT[w - 1]} ${h}:00–${h + 1}:00 · ${n} scarichi`}
                      className="h-8 min-w-8 rounded-[4px]"
                      style={{ background: color(n), outline: hover?.w === w && hover?.h === h ? "2px solid var(--foreground)" : undefined }}
                    >
                      <span className="sr-only">{n}</span>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap items-center gap-3 text-sm text-slate-500">
        <span className="flex items-center gap-1">
          0
          <span className="flex">
            {BLUES.filter((_, i) => i % 2 === 0).map((c) => (
              <span key={c} className="h-3 w-5" style={{ background: c }} />
            ))}
          </span>
          {max} scarichi
        </span>
        <span className="tabular-nums text-slate-700 dark:text-slate-300" aria-live="polite">
          {hover ? `${WEEKDAY_SHORT[hover.w - 1]} ${hover.h}:00–${hover.h + 1}:00 · ${hover.n} scarichi` : ""}
        </span>
      </div>
    </div>
  );
}

function UnloadingTable({ rows }: { rows: Report["unloading"] }) {
  const measured = rows.filter((r) => r.measured);
  if (!measured.length) return <p className="text-sm text-slate-500">Nessuno scarico completato nel periodo: registri «Inizio scarico» e «Scarico completato» dalla giornata.</p>;
  return (
    <SimpleTable
      head={["Mezzo", "Scarichi misurati", "Prenotato", "Mediana reale", "9 su 10 entro", ""]}
      rows={measured.map((r) => {
        const longer = r.medianMin !== null && r.plannedMin !== null && r.medianMin > r.plannedMin * 1.1;
        const shorter = r.medianMin !== null && r.plannedMin !== null && r.measured >= 5 && r.medianMin < r.plannedMin * 0.6;
        return [
          t.vehicles[r.vehicleType].name,
          r.measured,
          num(r.plannedMin, " min"),
          num(r.medianMin, " min"),
          num(r.p90Min, " min"),
          longer ? (
            <StatusNote key="l" kind="warning">
              Più lungo del previsto: valuti di aumentare la durata in «Durate»
            </StatusNote>
          ) : shorter ? (
            <StatusNote key="s" kind="info">
              Più breve del previsto: si potrebbero offrire più orari
            </StatusNote>
          ) : (
            ""
          ),
        ];
      })}
    />
  );
}

function CarriersTable({ rows }: { rows: Report["carriers"] }) {
  const [all, setAll] = useState(false);
  const shown = all ? rows : rows.slice(0, 15);
  return (
    <div className="flex flex-col gap-2">
      <p className="text-sm text-slate-500">
        Puntuale = arrivato entro 15 minuti dall&apos;orario prenotato (o prima). Segnalati: puntualità sotto il 70% o almeno 2 assenze e oltre il 20%.
      </p>
      <SimpleTable
        head={["Trasportatore", "Richieste", "Completate", "Puntualità", "Non presentati", "Annullate da loro"]}
        rows={shown.map((c) => [
          c.name,
          c.requests,
          c.completed,
          (c.onTimeRate ?? 1) < 0.7 && c.completed + c.noShows >= 5 ? (
            <StatusNote key="p" kind="warning">
              {pct(c.onTimeRate)} spesso in ritardo
            </StatusNote>
          ) : (
            pct(c.onTimeRate)
          ),
          c.noShows >= 2 && (c.noShowRate ?? 0) >= 0.2 ? (
            <StatusNote key="n" kind="warning">
              {c.noShows} ({pct(c.noShowRate)}) spesso assente
            </StatusNote>
          ) : (
            c.noShows
          ),
          c.cancelledByCarrier,
        ])}
        empty="Nessun trasportatore nel periodo."
      />
      {rows.length > 15 && (
        <button className="self-start text-sm text-blue-700 underline dark:text-blue-400" onClick={() => setAll(!all)}>
          {all ? "Mostra solo i primi 15" : `Mostra tutti (${rows.length})`}
        </button>
      )}
    </div>
  );
}

/** Status is never colour alone: icon + text. */
function StatusNote({ kind, children }: { kind: "warning" | "info"; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center gap-1.5 text-sm ${kind === "warning" ? "text-amber-800 dark:text-amber-300" : "text-slate-600 dark:text-slate-400"}`}>
      <span aria-hidden className={`grid h-4 w-4 shrink-0 place-items-center rounded-full text-[10px] font-bold text-white ${kind === "warning" ? "bg-[#d97706]" : "bg-slate-400"}`}>
        {kind === "warning" ? "!" : "i"}
      </span>
      {children}
    </span>
  );
}

function SimpleTable({ head, rows, empty }: { head: string[]; rows: ReactNode[][]; empty?: string }) {
  if (!rows.length) return <p className="text-sm text-slate-500">{empty}</p>;
  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-800">
      <table className="w-full text-left text-sm">
        <thead className="bg-slate-50 text-slate-500 dark:bg-slate-900">
          <tr>
            {head.map((h, i) => (
              <th key={i} className={`px-3 py-2 font-medium ${i > 0 && h ? "text-right" : ""}`}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
          {rows.map((r, i) => (
            <tr key={i}>
              {r.map((c, j) => (
                <td key={j} className={`px-3 py-2 ${j > 0 && typeof c !== "object" ? "text-right tabular-nums" : ""}`}>
                  {c}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DataTable({ summary, head, rows }: { summary: string; head: string[]; rows: ReactNode[][] }) {
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-blue-700 dark:text-blue-400">{summary}</summary>
      <div className="mt-2">
        <SimpleTable head={head} rows={rows} empty="Nessun dato." />
      </div>
    </details>
  );
}
