"use client";

import { useCallback, useEffect, useState } from "react";
import { formatDay } from "@/lib/i18n/messages";
import { Button, Field, inputClass } from "../ui";
import { Notice, type MeSite } from "./admin-console";
import { api, errorText } from "./api";

interface Closure {
  id: number;
  siteId: number | null;
  siteName: string | null;
  date: string;
  reason: string;
  editable: boolean;
}
interface Affected {
  code: string;
  time: string;
  carrierName: string;
  siteName: string;
}

export function ClosuresTab({ sites, isAdmin }: { sites: MeSite[]; isAdmin: boolean }) {
  const [list, setList] = useState<Closure[] | null>(null);
  const [siteId, setSiteId] = useState<string>(isAdmin ? "all" : String(sites[0]?.id ?? ""));
  const [date, setDate] = useState("");
  const [reason, setReason] = useState("");
  const [msg, setMsg] = useState<{ kind: "ok" | "warn" | "error"; text: React.ReactNode } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const r = await api<{ closures: Closure[] }>("GET", "/api/admin/closures");
    if (r.ok) setList(r.data.closures);
    else setMsg({ kind: "error", text: errorText(r) });
  }, []);
  useEffect(() => {
    load(); // eslint-disable-line react-hooks/set-state-in-effect -- initial fetch
  }, [load]);

  async function add() {
    setBusy(true);
    setMsg(null);
    const r = await api<{ affected: Affected[] }>("POST", "/api/admin/closures", {
      siteId: siteId === "all" ? null : Number(siteId),
      date,
      reason,
    });
    setBusy(false);
    if (!r.ok) return setMsg({ kind: "error", text: errorText(r) });
    setDate("");
    setReason("");
    await load();
    setMsg(
      r.data.affected.length
        ? {
            kind: "warn",
            text: (
              <>
                <p className="font-semibold">
                  Chiusura salvata.{" "}
                  {r.data.affected.length === 1 ? "C'è già 1 prenotazione" : `Ci sono già ${r.data.affected.length} prenotazioni`} in quel giorno:
                  restano valide finché non le annulla dalla pagina prenotazioni (il trasportatore riceve un&apos;e-mail).
                </p>
                <ul className="mt-1 list-disc pl-5">
                  {r.data.affected.map((b) => (
                    <li key={b.code}>
                      {b.time} · {b.siteName} · {b.carrierName} · <span className="font-mono">{b.code}</span>
                    </li>
                  ))}
                </ul>
              </>
            ),
          }
        : { kind: "ok", text: "Chiusura salvata. Il giorno non è più prenotabile." },
    );
  }

  async function remove(c: Closure) {
    const r = await api("DELETE", `/api/admin/closures/${c.id}`);
    if (!r.ok) return setMsg({ kind: "error", text: errorText(r) });
    setMsg({ kind: "ok", text: `Chiusura del ${c.date} rimossa: il giorno torna prenotabile.` });
    await load();
  }

  const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome" }).format(new Date());

  return (
    <div className="flex flex-col gap-5">
      <section className="flex flex-col gap-3 rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
        <h2 className="text-lg font-semibold">Aggiungi un giorno di chiusura</h2>
        <p className="text-sm text-slate-500">Inventario, chiusura straordinaria, festa del patrono. Le festività nazionali sono già inserite.</p>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Magazzino">
            {(id) => (
              <select id={id} value={siteId} onChange={(e) => setSiteId(e.target.value)} className={inputClass}>
                {isAdmin && <option value="all">Tutti i magazzini</option>}
                {sites.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Data">
            {(id) => <input id={id} type="date" min={today} value={date} onChange={(e) => setDate(e.target.value)} className={inputClass} />}
          </Field>
          <Field label="Motivo">
            {(id) => <input id={id} value={reason} maxLength={120} placeholder="Inventario" onChange={(e) => setReason(e.target.value)} className={inputClass} />}
          </Field>
        </div>
        <Button onClick={add} disabled={busy || !date || !reason.trim() || !siteId} className="self-start">
          Aggiungi chiusura
        </Button>
      </section>

      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}

      <section className="flex flex-col gap-2">
        <h2 className="text-lg font-semibold">Prossime chiusure</h2>
        {!list && <p className="text-slate-500">Caricamento…</p>}
        <ul className="divide-y divide-slate-200 rounded-xl border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
          {list?.map((c) => (
            <li key={c.id} className="flex items-center justify-between gap-3 px-4 py-2.5">
              <div>
                <div className="font-medium first-letter:uppercase">{formatDay(c.date, "it", { weekday: "long", day: "numeric", month: "long", year: "numeric" })}</div>
                <div className="text-sm text-slate-500">
                  {c.siteName ?? "Tutti i magazzini"} · {c.reason}
                </div>
              </div>
              {c.editable && (
                <button onClick={() => remove(c)} className="text-sm text-red-700 underline dark:text-red-400">
                  Rimuovi
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
