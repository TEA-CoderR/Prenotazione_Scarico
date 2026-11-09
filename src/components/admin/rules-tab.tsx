"use client";

import { useEffect, useState } from "react";
import { messages } from "@/lib/i18n/messages";
import type { SlotRule } from "@/lib/scheduling/duration";
import { Button, inputClass } from "../ui";
import { Notice } from "./admin-console";
import { api, errorText } from "./api";

const DURATIONS = Array.from({ length: 16 }, (_, i) => (i + 1) * 15);

export function RulesTab() {
  const [rules, setRules] = useState<SlotRule[] | null>(null);
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<{ slotRules: SlotRule[] }>("GET", "/api/admin/settings").then((r) => (r.ok ? setRules(r.data.slotRules) : setMsg({ kind: "error", text: errorText(r) })));
  }, []);

  if (!rules) return msg ? <Notice kind="error">{msg.text}</Notice> : <p className="text-slate-500">Caricamento…</p>;
  const set = (i: number, patch: Partial<SlotRule>) => setRules(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  async function save() {
    setBusy(true);
    const r = await api("PUT", "/api/admin/slot-rules", rules);
    setBusy(false);
    setMsg(r.ok ? { kind: "ok", text: "Salvato. Vale per le nuove prenotazioni di tutti i magazzini." } : { kind: "error", text: errorText(r) });
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-slate-600 dark:text-slate-300">
        Quanto tempo occupa la baia ogni tipo di mezzo, e quanto carico può dichiarare il trasportatore. Le prenotazioni già fatte non cambiano.
      </p>
      <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-800">
        <table className="w-full text-left">
          <thead className="bg-slate-50 text-sm text-slate-500 dark:bg-slate-900">
            <tr>
              <th className="px-4 py-2 font-medium">Mezzo</th>
              <th className="px-4 py-2 font-medium">Carico massimo</th>
              <th className="px-4 py-2 font-medium">Durata scarico</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
            {rules.map((r, i) => {
              const parcel = r.vehicleType === "parcel";
              const name = messages.it.vehicles[r.vehicleType].name;
              return (
                <tr key={r.vehicleType}>
                  <td className="px-4 py-2 font-medium">{name}</td>
                  <td className="px-4 py-2">
                    <span className="flex items-center gap-2">
                      <input
                        aria-label={`${name}: carico massimo`}
                        type="number"
                        min={parcel ? 1 : 0}
                        max={parcel ? 999 : 40}
                        value={parcel ? (r.maxParcels ?? "") : r.maxPallets}
                        onChange={(e) => set(i, parcel ? { maxParcels: Number(e.target.value) || null } : { maxPallets: Number(e.target.value) })}
                        className={`${inputClass} w-24 py-1.5`}
                      />
                      <span className="text-slate-500">{parcel ? "colli" : "pallet"}</span>
                    </span>
                  </td>
                  <td className="px-4 py-2">
                    <select
                      aria-label={`${name}: durata`}
                      value={r.durationMin}
                      onChange={(e) => set(i, { durationMin: Number(e.target.value) })}
                      className={`${inputClass} w-32 py-1.5`}
                    >
                      {DURATIONS.map((d) => (
                        <option key={d} value={d}>
                          {d} min
                        </option>
                      ))}
                    </select>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <Button onClick={save} disabled={busy} className="self-start">
        Salva durate
      </Button>
    </div>
  );
}
