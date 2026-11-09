"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import type { BookingStatus } from "@/db/schema";
import { formatDay, formatInstant } from "@/lib/i18n/messages";
import { useLang } from "./lang";
import { Button, buttonClass, ErrorNote } from "./ui";

interface ManagedBooking {
  code: string;
  status: BookingStatus;
  entityName: string;
  siteName: string;
  siteAddress: string | null;
  date: string;
  time: string;
  endTime: string;
  vehicleType: "parcel" | "van" | "truck" | "semi";
  pallets: number;
  parcels: number;
  canCancel: boolean;
  cancelDeadline: string;
}

const STATUS_STYLE: Record<BookingStatus, string> = {
  requested: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200",
  confirmed: "bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  arrived: "bg-blue-100 text-blue-900 dark:bg-blue-950 dark:text-blue-200",
  unloading: "bg-blue-100 text-blue-900 dark:bg-blue-950 dark:text-blue-200",
  completed: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200",
  rejected: "bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-200",
  cancelled: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200",
  no_show: "bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-200",
};

export function ManageBooking() {
  const { token } = useParams<{ token: string }>();
  const { t, lang } = useLang();
  const [booking, setBooking] = useState<ManagedBooking | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [justCancelled, setJustCancelled] = useState(false);

  useEffect(() => {
    fetch(`/api/manage/${encodeURIComponent(token)}`, { cache: "no-store" })
      .then(async (res) => {
        const body = await res.json().catch(() => ({}));
        if (res.ok) setBooking(body.booking);
        else setError(body.error ?? "GENERIC");
      })
      .catch(() => setError("GENERIC"));
  }, [token]);

  async function cancel() {
    setBusy(true);
    try {
      const res = await fetch(`/api/manage/${encodeURIComponent(token)}`, { method: "DELETE" });
      const body = await res.json().catch(() => ({}));
      if (res.ok) {
        setBooking(body.booking);
        setJustCancelled(true);
      } else setError(body.error ?? "GENERIC");
    } catch {
      setError("GENERIC");
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  }

  if (error && !booking) return <ErrorNote>{t.errors[error] ?? t.errors.GENERIC}</ErrorNote>;
  if (!booking) return <p className="text-slate-500">{t.loading}</p>;

  const b = booking;
  const load = b.vehicleType === "parcel" ? `${b.parcels} ${t.unitParcels}` : `${b.pallets} ${t.unitPallets}`;
  const live = b.status === "requested" || b.status === "confirmed";

  return (
    <section className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">{t.manageTitle}</h1>
        <span className={`rounded-full px-3 py-1 text-sm font-semibold ${STATUS_STYLE[b.status]}`} data-testid="status">
          {t.status[b.status]}
        </span>
      </div>

      <div className="rounded-2xl border border-slate-200 p-5 dark:border-slate-800">
        <div className="font-mono text-2xl font-semibold tracking-wider">{b.code}</div>
        <div className="mt-3 text-lg font-semibold">
          {formatDay(b.date, lang, { weekday: "long", day: "numeric", month: "long" })}, {b.time}–{b.endTime}
        </div>
        <div className="mt-1 text-slate-600 dark:text-slate-300">
          {b.entityName} · {b.siteName}
          {b.siteAddress ? ` · ${b.siteAddress}` : ""}
        </div>
        <div className="mt-1 text-slate-600 dark:text-slate-300">
          {t.vehicles[b.vehicleType].name} · {load}
        </div>
      </div>

      {justCancelled && <p className="text-slate-700 dark:text-slate-200">{t.cancelled}</p>}
      {error && <ErrorNote>{t.errors[error] ?? t.errors.GENERIC}</ErrorNote>}

      {live && b.canCancel && (
        <div className="flex flex-col gap-3">
          <p className="text-sm text-slate-500">
            {t.cancelUntil(formatInstant(b.cancelDeadline, lang))} {t.changeHint}
          </p>
          {confirming ? (
            <div className="flex gap-3">
              <Button variant="secondary" className="flex-1" onClick={() => setConfirming(false)} disabled={busy}>
                {t.cancelKeep}
              </Button>
              <Button variant="danger" className="flex-1" onClick={cancel} disabled={busy}>
                {t.cancelConfirm}
              </Button>
            </div>
          ) : (
            <Button variant="secondary" onClick={() => setConfirming(true)}>
              {t.cancel}
            </Button>
          )}
        </div>
      )}
      {live && !b.canCancel && <p className="text-sm text-slate-600 dark:text-slate-300">{t.cancelClosed}</p>}

      {!live && (
        <Link href="/" className={buttonClass("primary")}>
          {t.doneNew}
        </Link>
      )}
    </section>
  );
}
