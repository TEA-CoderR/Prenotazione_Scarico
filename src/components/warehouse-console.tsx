"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { BookingStatus } from "@/db/schema";
import type { Board, StaffBooking } from "@/lib/booking/staff";
import { formatDay, messages } from "@/lib/i18n/messages";
import { Button, ErrorNote, inputClass } from "./ui";

const t = messages.it; // staff area is Italian
const REFRESH_MS = 30_000;
const SITE_KEY = "staffSite";

interface Me {
  user: { name: string; email: string; role: "warehouse_clerk" | "warehouse_manager" | "admin" };
  sites: { id: number; code: string; name: string }[];
}

const STATUS_STYLE: Record<BookingStatus, string> = {
  requested: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200",
  confirmed: "bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  arrived: "bg-blue-100 text-blue-900 dark:bg-blue-950 dark:text-blue-200",
  unloading: "bg-blue-700 text-white",
  completed: "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300",
  rejected: "bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-200",
  cancelled: "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300",
  no_show: "bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-200",
};

const ACTION_ERRORS: Record<string, string> = {
  TOO_EARLY: "Si può segnare «non presentato» solo 30 minuti dopo l'orario prenotato.",
  INVALID_TRANSITION: "La prenotazione è già stata modificata. Elenco aggiornato.",
  FORBIDDEN: "Operazione non consentita per il suo ruolo.",
  VALIDATION: "Indichi il motivo per il trasportatore.",
};

const REJECT_REASONS = ["Magazzino al completo in quell'orario", "Consegna non prevista: contatti l'ufficio acquisti"];

function romeToday(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome" }).format(new Date());
}

function groupByDate(list: StaffBooking[]): [string, StaffBooking[]][] {
  const groups = new Map<string, StaffBooking[]>();
  for (const b of list) groups.set(b.date, [...(groups.get(b.date) ?? []), b]);
  return [...groups.entries()];
}

function shiftDay(ymd: string, days: number): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

function hoursAgo(iso: string): string {
  const min = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${min % 60} min`;
}

export function WarehouseConsole() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [siteId, setSiteId] = useState<number | null>(null);
  // Set in the browser once signed in (not during prerender, where "today" is unknown).
  const [today, setToday] = useState<string | null>(null);
  const [date, setDate] = useState<string | null>(null);
  // Until the first load: then "Da confermare" if requests are waiting, else "Prossimi".
  const [tab, setTab] = useState<"pending" | "upcoming" | "day" | null>(null);
  // First day of the 14-day strip in "Giornata".
  const [stripFrom, setStripFrom] = useState<string | null>(null);
  const [board, setBoard] = useState<Board | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  useEffect(() => {
    fetch("/api/staff/me", { cache: "no-store" })
      .then(async (res) => {
        if (res.status === 401) return router.replace("/magazzino/login");
        if (res.status === 403) return router.replace("/magazzino/2fa");
        const data = (await res.json()) as Me;
        const now = romeToday();
        setToday(now);
        setDate(now);
        setStripFrom(now);
        setMe(data);
        let stored: number | null = null;
        try {
          stored = Number(localStorage.getItem(SITE_KEY)) || null;
        } catch {}
        setSiteId(data.sites.find((s) => s.id === stored)?.id ?? data.sites[0]?.id ?? null);
      })
      .catch(() => setLoadError(true));
  }, [router]);

  const refresh = useCallback(async () => {
    if (!siteId || !date || !stripFrom) return;
    try {
      const res = await fetch(`/api/staff/board?site=${siteId}&date=${date}&strip=${stripFrom}`, { cache: "no-store" });
      if (res.status === 401) return router.replace("/magazzino/login");
      if (res.status === 403 && (await res.clone().json().catch(() => ({}))).error === "MFA_REQUIRED") return router.replace("/magazzino/2fa");
      if (!res.ok) throw new Error(String(res.status));
      const data = (await res.json()) as Board;
      setBoard(data);
      setTab((current) => current ?? (data.pending.length ? "pending" : "upcoming"));
      setLoadError(false);
    } catch {
      setLoadError(true);
    }
  }, [siteId, date, stripFrom, router]);

  // Load now, every 30 s, and whenever the tablet wakes up.
  useEffect(() => {
    const first = setTimeout(refresh, 0);
    const timer = setInterval(refresh, REFRESH_MS);
    const onVisible = () => document.visibilityState === "visible" && refresh();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearTimeout(first);
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [refresh]);

  async function setStatus(b: StaffBooking, to: BookingStatus, note?: string) {
    setBusyId(b.id);
    setActionError(null);
    try {
      const res = await fetch(`/api/staff/bookings/${b.id}/status`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ to, note }),
      });
      if (res.status === 401) return router.replace("/magazzino/login");
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        setActionError(ACTION_ERRORS[body.error] ?? "Operazione non riuscita. Riprovi.");
      }
      await refresh();
      return res.ok;
    } finally {
      setBusyId(null);
    }
  }

  async function logout() {
    await fetch("/api/auth/logout", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" });
    router.replace("/magazzino/login");
  }

  if (!me || !date || !today || !stripFrom) return <p className="p-4 text-slate-500">{loadError ? "Connessione non riuscita." : t.loading}</p>;
  if (!me.sites.length) return <ErrorNote>Nessun magazzino assegnato a questo utente. Contatti l&apos;amministratore.</ErrorNote>;

  const canCancel = me.user.role !== "warehouse_clerk";
  // While another day is loading, show nothing rather than the previous day's list.
  const dayLoaded = board?.date === date;
  const list = tab === "pending" ? board?.pending : tab === "day" && dayLoaded ? board?.day : undefined;

  /** Open "Giornata" on a date, moving the strip if the date is outside it. */
  function goToDay(d: string) {
    setDate(d);
    setTab("day");
    if (stripFrom && (d < stripFrom || d >= shiftDay(stripFrom, 14))) setStripFrom(d);
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        {me.sites.length > 1 ? (
          <select
            aria-label="Magazzino"
            value={siteId ?? ""}
            onChange={(e) => {
              const id = Number(e.target.value);
              setSiteId(id);
              setBoard(null);
              try {
                localStorage.setItem(SITE_KEY, String(id));
              } catch {}
            }}
            className={`${inputClass} w-auto text-lg font-semibold`}
          >
            {me.sites.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        ) : (
          <h1 className="text-xl font-semibold">{me.sites[0].name}</h1>
        )}
        <div className="flex items-center gap-3 text-sm text-slate-500">
          <span>{me.user.name}</span>
          {me.user.role !== "warehouse_clerk" && (
            <Link href="/magazzino/admin" className="font-medium text-blue-700 underline dark:text-blue-400">
              {me.user.role === "admin" ? "Amministrazione" : "Report e chiusure"}
            </Link>
          )}
          <Link href="/magazzino/account" className="underline">
            Password
          </Link>
          <button onClick={logout} className="underline">
            Esci
          </button>
        </div>
      </div>

      <div className="grid grid-cols-3 gap-1 rounded-xl bg-slate-100 p-1 dark:bg-slate-900" role="tablist">
        {(
          [
            ["pending", "Da confermare", board?.pending.length],
            ["upcoming", "Prossimi", board?.upcoming.length],
            ["day", "Giornata", undefined],
          ] as const
        ).map(([key, label, n]) => (
          <button
            key={key}
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={`flex min-h-11 flex-wrap items-center justify-center gap-x-1.5 rounded-lg px-1 text-sm font-semibold sm:text-base ${tab === key ? "bg-white shadow-sm dark:bg-slate-800" : "text-slate-600 dark:text-slate-400"}`}
          >
            {label}
            {n !== undefined && (
              <span
                className={`rounded-full px-2 text-xs tabular-nums ${key === "pending" && n > 0 ? "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200" : "bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-200"}`}
              >
                {n}
              </span>
            )}
          </button>
        ))}
      </div>

      {tab === "day" && board && (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="text-lg font-semibold first-letter:uppercase">
              {formatDay(date, "it", { weekday: "long", day: "numeric", month: "long" })}
            </div>
            <div className="flex items-center gap-2">
              {date !== today && (
                <button className="text-sm text-blue-700 underline dark:text-blue-400" onClick={() => goToDay(today)}>
                  Oggi
                </button>
              )}
              <input
                type="date"
                aria-label="Vai al giorno"
                value={date}
                onChange={(e) => e.target.value && goToDay(e.target.value)}
                className={`${inputClass} w-auto py-1.5`}
              />
            </div>
          </div>
          <div className="flex items-stretch gap-1">
            <button
              aria-label="Settimana precedente"
              onClick={() => setStripFrom(shiftDay(stripFrom, -7))}
              className="rounded-lg border border-slate-300 px-2 text-lg dark:border-slate-700"
            >
              ‹
            </button>
            <div className="grid flex-1 grid-cols-7 gap-1" role="radiogroup" aria-label="Giorni">
              {board.days.map((d) => {
                const selected = d.date === date;
                return (
                  <button
                    key={d.date}
                    role="radio"
                    aria-checked={selected}
                    aria-label={`${formatDay(d.date, "it", { weekday: "long", day: "numeric", month: "long" })}: ${d.open ? `${d.bookings} scarichi` : "chiuso"}`}
                    onClick={() => goToDay(d.date)}
                    className={`relative flex flex-col items-center rounded-lg border px-0.5 py-1.5 text-xs transition-colors ${
                      selected
                        ? "border-blue-700 bg-blue-700 text-white"
                        : d.open
                          ? "border-slate-300 hover:border-blue-700 dark:border-slate-700"
                          : "border-transparent bg-slate-50 text-slate-400 dark:bg-slate-900 dark:text-slate-600"
                    }`}
                  >
                    <span className="uppercase">{formatDay(d.date, "it", { weekday: "short" }).replace(".", "")}</span>
                    <span className={`text-base font-semibold leading-tight ${d.date === today && !selected ? "text-blue-700 underline dark:text-blue-400" : ""}`}>
                      {formatDay(d.date, "it", { day: "numeric" })}
                    </span>
                    <span
                      className={`mt-0.5 min-w-6 rounded-full px-1.5 text-center font-semibold tabular-nums ${
                        d.bookings === 0
                          ? selected ? "text-blue-100" : "text-slate-400"
                          : selected
                            ? "bg-white text-blue-800"
                            : "bg-blue-100 text-blue-900 dark:bg-blue-950 dark:text-blue-200"
                      }`}
                    >
                      {d.open || d.bookings ? d.bookings : "–"}
                    </span>
                    {d.pending > 0 && (
                      <span
                        className="absolute right-1 top-1 h-2 w-2 rounded-full bg-amber-500"
                        title={`${d.pending} da confermare`}
                        aria-hidden
                      />
                    )}
                  </button>
                );
              })}
            </div>
            <button
              aria-label="Settimana successiva"
              onClick={() => setStripFrom(shiftDay(stripFrom, 7))}
              className="rounded-lg border border-slate-300 px-2 text-lg dark:border-slate-700"
            >
              ›
            </button>
          </div>
          <p className="text-xs text-slate-500">
            Il numero indica gli scarichi del giorno; il punto arancione indica richieste ancora da confermare.
          </p>
        </div>
      )}

      {loadError && <ErrorNote>Aggiornamento non riuscito. Riprovo automaticamente.</ErrorNote>}
      {actionError && <ErrorNote>{actionError}</ErrorNote>}

      {tab === "day" && board && !dayLoaded && <p className="text-slate-500">{t.loading}</p>}
      {tab === "day" && board && dayLoaded && board.day.length > 0 && (
        <p className="text-sm text-slate-500">
          Scarichi: {board.day.filter((b) => !["rejected", "cancelled"].includes(b.status)).length} · Completati:{" "}
          {board.day.filter((b) => b.status === "completed").length} · Pallet:{" "}
          {board.day.reduce((n, b) => n + (["rejected", "cancelled", "no_show"].includes(b.status) ? 0 : b.pallets), 0)}
        </p>
      )}

      {!board && !loadError && <p className="text-slate-500">{t.loading}</p>}
      {list && list.length === 0 && (
        <p className="rounded-xl border border-dashed border-slate-300 p-6 text-center text-slate-500 dark:border-slate-700">
          {tab === "pending" ? "Nessuna richiesta da confermare." : "Nessuna prenotazione in questo giorno."}
        </p>
      )}

      {tab === "upcoming" && board && (
        <div className="flex flex-col gap-5">
          {board.upcoming.length === 0 && (
            <p className="rounded-xl border border-dashed border-slate-300 p-6 text-center text-slate-500 dark:border-slate-700">
              Nessuna prenotazione nei prossimi 31 giorni.
            </p>
          )}
          {groupByDate(board.upcoming).map(([d, items]) => (
            <section key={d} className="flex flex-col gap-2">
              <div className="sticky top-0 z-10 -mx-1 flex items-baseline justify-between gap-2 bg-[var(--background)] px-1 py-1">
                <h2 className="text-lg font-semibold first-letter:uppercase">
                  {d === today ? "Oggi, " : ""}
                  {formatDay(d, "it", { weekday: "long", day: "numeric", month: "long" })}
                </h2>
                <button className="shrink-0 text-sm text-blue-700 underline dark:text-blue-400" onClick={() => goToDay(d)}>
                  {items.length} {items.length === 1 ? "scarico" : "scarichi"} · apri giornata
                </button>
              </div>
              <ul className="flex flex-col gap-3">
                {items.map((b) => (
                  <BookingCard key={b.id} b={b} showDate={false} busy={busyId === b.id} canCancel={canCancel} onStatus={setStatus} />
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}

      <ul className="flex flex-col gap-3">
        {list?.map((b) => (
          <BookingCard key={b.id} b={b} showDate={tab === "pending"} busy={busyId === b.id} canCancel={canCancel} onStatus={setStatus} />
        ))}
      </ul>
    </div>
  );
}

function BookingCard(props: {
  b: StaffBooking;
  showDate: boolean;
  busy: boolean;
  canCancel: boolean;
  onStatus: (b: StaffBooking, to: BookingStatus, note?: string) => Promise<boolean | void>;
}) {
  const { b, busy } = props;
  const [mode, setMode] = useState<null | "reject" | "cancel">(null);
  const [note, setNote] = useState("");
  const vehicle = t.vehicles[b.vehicleType as keyof typeof t.vehicles]?.name ?? b.vehicleType;
  const load = b.vehicleType === "parcel" ? `${b.parcels} colli` : `${b.pallets} pallet`;
  const closed = ["completed", "rejected", "cancelled", "no_show"].includes(b.status);

  const details: [string, ReactNode][] = [
    ["Trasportatore", b.carrierName],
    ["E-mail", <a key="m" href={`mailto:${b.contactEmail}`} className="text-blue-700 underline dark:text-blue-400">{b.contactEmail}</a>],
  ];
  if (b.driverName || b.driverPhone)
    details.push([
      "Autista",
      <span key="d">
        {b.driverName}
        {b.driverPhone && (
          <>
            {b.driverName ? " · " : ""}
            <a href={`tel:${b.driverPhone}`} className="text-blue-700 underline dark:text-blue-400">{b.driverPhone}</a>
          </>
        )}
      </span>,
    ]);
  if (b.plate) details.push(["Targa", b.plate]);
  if (b.supplier) details.push(["Fornitore", b.supplier]);
  if (b.orderNo) details.push(["Ordine", b.orderNo]);
  if (b.ddtNo) details.push(["DDT", b.ddtNo]);
  if (b.notes) details.push(["Note", b.notes]);

  async function send(to: BookingStatus) {
    const ok = await props.onStatus(b, to, note || undefined);
    if (ok) {
      setMode(null);
      setNote("");
    }
  }

  return (
    <li className={`rounded-2xl border p-4 ${closed ? "border-slate-200 opacity-70 dark:border-slate-800" : "border-slate-300 dark:border-slate-700"}`} data-testid={`booking-${b.code}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="text-2xl font-semibold tabular-nums">
            {b.time}
            <span className="text-base font-normal text-slate-500">–{b.endTime}</span>
            {props.showDate && (
              <span className="ml-2 text-base font-medium text-slate-600 first-letter:uppercase dark:text-slate-300">
                {formatDay(b.date, "it")}
              </span>
            )}
          </div>
          <div className="mt-0.5 font-semibold">
            {b.entityName} <span className="text-sm font-normal text-slate-500">({b.entityCode})</span> · {vehicle} · {load}
          </div>
        </div>
        <div className="flex flex-col items-end gap-1">
          <span className={`rounded-full px-3 py-1 text-sm font-semibold ${STATUS_STYLE[b.status]}`}>{t.status[b.status]}</span>
          <span className="font-mono text-sm text-slate-500">{b.code}</span>
        </div>
      </div>

      {b.overdue && (
        <p className="mt-2 rounded-lg bg-red-50 px-3 py-1.5 text-sm font-medium text-red-800 dark:bg-red-950/50 dark:text-red-200">
          In attesa da {hoursAgo(b.createdAt)}: il trasportatore aspetta una risposta.
        </p>
      )}

      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        {details.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-slate-500">{k}</dt>
            <dd className="break-words">{v}</dd>
          </div>
        ))}
      </dl>

      {mode && (
        <div className="mt-3 flex flex-col gap-2">
          <label className="text-sm font-medium" htmlFor={`note-${b.id}`}>
            {mode === "reject" ? "Motivo del rifiuto (inviato al trasportatore)" : "Motivo dell'annullamento (inviato al trasportatore)"}
          </label>
          {mode === "reject" && (
            <div className="flex flex-wrap gap-2">
              {REJECT_REASONS.map((r) => (
                <button key={r} type="button" onClick={() => setNote(r)} className="rounded-full border border-slate-300 px-3 py-1 text-sm dark:border-slate-700">
                  {r}
                </button>
              ))}
            </div>
          )}
          <textarea id={`note-${b.id}`} rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} className={inputClass} />
        </div>
      )}

      {!closed && (
        <div className="mt-4 flex flex-wrap gap-2">
          {mode ? (
            <>
              <Button variant="secondary" onClick={() => setMode(null)} disabled={busy}>
                Indietro
              </Button>
              <Button variant="danger" className="flex-1" disabled={busy || (mode === "reject" && !note.trim())} onClick={() => send(mode === "reject" ? "rejected" : "cancelled")}>
                {mode === "reject" ? "Invia rifiuto" : "Conferma annullamento"}
              </Button>
            </>
          ) : (
            <>
              {b.status === "requested" && (
                <>
                  <Button className="flex-1 !bg-emerald-700 hover:!bg-emerald-800" disabled={busy} onClick={() => send("confirmed")}>
                    Conferma
                  </Button>
                  <Button variant="secondary" disabled={busy} onClick={() => setMode("reject")}>
                    Rifiuta
                  </Button>
                </>
              )}
              {b.status === "confirmed" && (
                <>
                  <Button className="flex-1" disabled={busy} onClick={() => send("arrived")}>
                    Arrivato
                  </Button>
                  {b.noShowAllowed && (
                    <Button variant="secondary" disabled={busy} onClick={() => send("no_show")}>
                      Non presentato
                    </Button>
                  )}
                </>
              )}
              {b.status === "arrived" && (
                <Button className="flex-1" disabled={busy} onClick={() => send("unloading")}>
                  Inizio scarico
                </Button>
              )}
              {b.status === "unloading" && (
                <Button className="flex-1" disabled={busy} onClick={() => send("completed")}>
                  Scarico completato
                </Button>
              )}
              {props.canCancel && (b.status === "requested" || b.status === "confirmed") && (
                <button className="px-2 text-sm text-slate-500 underline" disabled={busy} onClick={() => setMode("cancel")}>
                  Annulla
                </button>
              )}
            </>
          )}
        </div>
      )}
    </li>
  );
}
