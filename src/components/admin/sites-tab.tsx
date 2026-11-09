"use client";

import { useCallback, useEffect, useState } from "react";
import { Button, Field, inputClass } from "../ui";
import { Notice, WEEKDAYS } from "./admin-console";
import { api, errorText } from "./api";

interface Range {
  weekday: number;
  opens: string;
  closes: string;
}
interface Policy {
  maxDaysAhead: number;
  bookingCutoffTime: string;
  cancelCutoffHours: number;
  noShowAfterMin: number;
  confirmSlaHours: number;
  dailyPalletCap: number | null;
}
export interface SiteSettings {
  id: number;
  code: string;
  name: string;
  address: string | null;
  docks: number;
  hours: Range[];
  policy: Policy;
}

export function SitesTab() {
  const [sites, setSites] = useState<SiteSettings[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [form, setForm] = useState<SiteSettings | null>(null);
  const [msg, setMsg] = useState<{ kind: "ok" | "warn" | "error"; text: React.ReactNode } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (keep?: number) => {
    const r = await api<{ sites: SiteSettings[] }>("GET", "/api/admin/settings");
    if (!r.ok) return setMsg({ kind: "error", text: errorText(r) });
    setSites(r.data.sites);
    const id = keep ?? r.data.sites[0]?.id ?? null;
    setSelected(id);
    setForm(structuredClone(r.data.sites.find((s) => s.id === id) ?? null));
  }, []);
  useEffect(() => {
    load(); // eslint-disable-line react-hooks/set-state-in-effect -- initial fetch
  }, [load]);

  if (!sites || !form) return msg ? <Notice kind="error">{msg.text}</Notice> : <p className="text-slate-500">Caricamento…</p>;

  const setPolicy = <K extends keyof Policy>(k: K, v: Policy[K]) => setForm({ ...form, policy: { ...form.policy, [k]: v } });
  const dayRanges = (d: number) => form.hours.map((h, i) => ({ ...h, i })).filter((h) => h.weekday === d);
  const setRange = (i: number, patch: Partial<Range>) => setForm({ ...form, hours: form.hours.map((h, j) => (j === i ? { ...h, ...patch } : h)) });

  async function save() {
    if (!form) return;
    setBusy(true);
    setMsg(null);
    const r = await api<{ outside: { code: string; date: string; time: string; carrierName: string }[] }>("PUT", `/api/admin/sites/${form.id}`, {
      name: form.name,
      address: form.address,
      hours: form.hours,
      policy: form.policy,
    });
    setBusy(false);
    if (!r.ok) return setMsg({ kind: "error", text: errorText(r) });
    await load(form.id);
    setMsg(
      r.data.outside.length
        ? {
            kind: "warn",
            text: (
              <>
                <p className="font-semibold">Salvato. Queste prenotazioni sono ora fuori orario e restano valide: contatti i trasportatori.</p>
                <ul className="mt-1 list-disc pl-5">
                  {r.data.outside.map((b) => (
                    <li key={b.code}>
                      {b.date} {b.time} · {b.carrierName} · <span className="font-mono">{b.code}</span>
                    </li>
                  ))}
                </ul>
              </>
            ),
          }
        : { kind: "ok", text: "Salvato. I trasportatori vedono subito i nuovi orari." },
    );
  }

  return (
    <div className="flex flex-col gap-5">
      <select
        aria-label="Magazzino da modificare"
        value={selected ?? ""}
        onChange={(e) => {
          const id = Number(e.target.value);
          setSelected(id);
          setForm(structuredClone(sites.find((s) => s.id === id)!));
          setMsg(null);
        }}
        className={`${inputClass} w-auto text-lg font-semibold`}
      >
        {sites.map((s) => (
          <option key={s.id} value={s.id}>
            {s.name}
          </option>
        ))}
      </select>

      <section className="grid gap-4 sm:grid-cols-2">
        <Field label="Nome del magazzino">
          {(id) => <input id={id} value={form.name} maxLength={120} onChange={(e) => setForm({ ...form, name: e.target.value })} className={inputClass} />}
        </Field>
        <Field label="Indirizzo" hint="mostrato ai trasportatori e nelle e-mail">
          {(id) => (
            <input id={id} value={form.address ?? ""} maxLength={200} onChange={(e) => setForm({ ...form, address: e.target.value })} className={inputClass} />
          )}
        </Field>
      </section>
      <p className="-mt-2 text-sm text-slate-500">
        Baie di scarico: {form.docks} · codice {form.code}
      </p>

      <section className="flex flex-col gap-2">
        <h2 className="text-lg font-semibold">Orari di ricevimento</h2>
        <p className="text-sm text-slate-500">Orari ai quarti d&apos;ora. Per la pausa pranzo aggiunga una seconda fascia.</p>
        <div className="divide-y divide-slate-200 rounded-xl border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
          {WEEKDAYS.map((name, idx) => {
            const d = idx + 1;
            const ranges = dayRanges(d);
            return (
              <div key={d} className="flex flex-wrap items-center gap-3 px-4 py-2.5" data-testid={`day-${d}`}>
                <span className="w-24 font-medium">{name}</span>
                {ranges.length === 0 && <span className="text-slate-500">Chiuso</span>}
                {ranges.map((r) => (
                  <span key={r.i} className="flex items-center gap-1">
                    <input aria-label={`${name} apertura`} type="time" step={900} value={r.opens} onChange={(e) => setRange(r.i, { opens: e.target.value })} className={`${inputClass} w-28 py-1.5`} />
                    –
                    <input aria-label={`${name} chiusura`} type="time" step={900} value={r.closes} onChange={(e) => setRange(r.i, { closes: e.target.value })} className={`${inputClass} w-28 py-1.5`} />
                    <button
                      type="button"
                      aria-label={`Rimuovi fascia ${name}`}
                      onClick={() => setForm({ ...form, hours: form.hours.filter((_, j) => j !== r.i) })}
                      className="px-2 text-xl text-slate-400 hover:text-red-700"
                    >
                      ×
                    </button>
                  </span>
                ))}
                <button
                  type="button"
                  onClick={() => {
                    const last = ranges.at(-1);
                    const next = last ? { opens: last.closes < "23:00" ? last.closes : "09:00", closes: "18:00" } : { opens: "09:00", closes: "18:00" };
                    setForm({ ...form, hours: [...form.hours, { weekday: d, ...next }] });
                  }}
                  className="text-sm text-blue-700 underline dark:text-blue-400"
                >
                  + fascia
                </button>
              </div>
            );
          })}
        </div>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold">Regole di prenotazione</h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <NumberField label="Prenotabile fino a (giorni in anticipo)" value={form.policy.maxDaysAhead} min={1} max={90} onChange={(v) => setPolicy("maxDaysAhead", v ?? 14)} />
          <Field label="Chiusura prenotazioni per il giorno dopo" hint="ora del giorno prima">
            {(id) => (
              <input id={id} type="time" value={form.policy.bookingCutoffTime} onChange={(e) => setPolicy("bookingCutoffTime", e.target.value)} className={inputClass} />
            )}
          </Field>
          <NumberField label="Annullamento online fino a (ore prima)" value={form.policy.cancelCutoffHours} min={0} max={72} onChange={(v) => setPolicy("cancelCutoffHours", v ?? 12)} />
          <NumberField label="«Non presentato» dopo (minuti di ritardo)" value={form.policy.noShowAfterMin} min={5} max={240} onChange={(v) => setPolicy("noShowAfterMin", v ?? 30)} />
          <NumberField label="Confermare le richieste entro (ore)" value={form.policy.confirmSlaHours} min={1} max={72} onChange={(v) => setPolicy("confirmSlaHours", v ?? 4)} />
          <NumberField label="Massimo pallet al giorno" hint="vuoto = nessun limite" value={form.policy.dailyPalletCap} min={1} max={2000} onChange={(v) => setPolicy("dailyPalletCap", v)} />
        </div>
      </section>

      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <div className="sticky bottom-0 -mx-4 border-t border-slate-200 bg-[var(--background)] px-4 py-3 dark:border-slate-800">
        <Button onClick={save} disabled={busy} className="w-full sm:w-auto">
          {busy ? "Salvataggio…" : "Salva modifiche"}
        </Button>
      </div>
    </div>
  );
}

export function NumberField(props: { label: string; hint?: string; value: number | null; min: number; max: number; onChange: (v: number | null) => void }) {
  return (
    <Field label={props.label} hint={props.hint}>
      {(id) => (
        <input
          id={id}
          type="number"
          inputMode="numeric"
          min={props.min}
          max={props.max}
          value={props.value ?? ""}
          onChange={(e) => props.onChange(e.target.value === "" ? null : Number(e.target.value))}
          className={inputClass}
        />
      )}
    </Field>
  );
}
