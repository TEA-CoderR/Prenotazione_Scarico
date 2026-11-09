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
  const [tab, setTab] = useState<"pending" | "day">("pending");
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
    if (!siteId || !date) return;
    try {
      const res = await fetch(`/api/staff/board?site=${siteId}&date=${date}`, { cache: "no-store" });
      if (res.status === 401) return router.replace("/magazzino/login");
      if (res.status === 403 && (await res.clone().json().catch(() => ({}))).error === "MFA_REQUIRED") return router.replace("/magazzino/2fa");
      if (!res.ok) throw new Error(String(res.status));
      setBoard(await res.json());
      setLoadError(false);
    } catch {
      setLoadError(true);
    }
  }, [siteId, date, router]);

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

  if (!me || !date || !today) return <p className="p-4 text-slate-500">{loadError ? "Connessione non riuscita." : t.loading}</p>;
  if (!me.sites.length) return <ErrorNote>Nessun magazzino assegnato a questo utente. Contatti l&apos;amministratore.</ErrorNote>;

  const canCancel = me.user.role !== "warehouse_clerk";
  const list = tab === "pending" ? board?.pending : board?.day;

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
              {me.user.role === "admin" ? "Amministrazione" : "Chiusure"}
            </Link>
          )}
          <button onClick={logout} className="underline">
            Esci
          </button>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-1 rounded-xl bg-slate-100 p-1 dark:bg-slate-900" role="tablist">
        {(
          [
            ["pending", `Da confermare${board ? ` (${board.pending.length})` : ""}`],
            ["day", "Giornata"],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={`min-h-11 rounded-lg font-semibold ${tab === key ? "bg-white shadow-sm dark:bg-slate-800" : "text-slate-600 dark:text-slate-400"}`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === "day" && (
        <div className="flex items-center justify-between gap-2">
          <Button variant="secondary" aria-label="Giorno precedente" onClick={() => setDate(shiftDay(date, -1))}>
            ‹
          </Button>
          <div className="text-center">
            <div className="text-lg font-semibold first-letter:uppercase">
              {formatDay(date, "it", { weekday: "long", day: "numeric", month: "long" })}
            </div>
            {date !== today && (
              <button className="text-sm text-blue-700 underline dark:text-blue-400" onClick={() => setDate(today)}>
                Torna a oggi
              </button>
            )}
          </div>
          <Button variant="secondary" aria-label="Giorno successivo" onClick={() => setDate(shiftDay(date, 1))}>
            ›
          </Button>
        </div>
      )}

      {loadError && <ErrorNote>Aggiornamento non riuscito. Riprovo automaticamente.</ErrorNote>}
      {actionError && <ErrorNote>{actionError}</ErrorNote>}

      {tab === "day" && board && board.day.length > 0 && (
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
