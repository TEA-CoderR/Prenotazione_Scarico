"use client";

import { useEffect, useState } from "react";
import { Button, inputClass } from "../ui";
import { Notice } from "./admin-console";
import { api, errorText } from "./api";

interface Entity {
  id: number;
  code: number;
  name: string;
  legalName: string | null;
  siteId: number;
  isActive: boolean;
}

export function EntitiesTab() {
  const [rows, setRows] = useState<Entity[] | null>(null);
  const [siteNames, setSiteNames] = useState<Record<number, string>>({});
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  useEffect(() => {
    api<{ entities: Entity[]; sites: { id: number; name: string }[] }>("GET", "/api/admin/settings").then((r) => {
      if (!r.ok) return setMsg({ kind: "error", text: errorText(r) });
      setRows(r.data.entities);
      setSiteNames(Object.fromEntries(r.data.sites.map((s) => [s.id, s.name])));
    });
  }, []);

  if (!rows) return msg ? <Notice kind="error">{msg.text}</Notice> : <p className="text-slate-500">Caricamento…</p>;

  async function save(e: Entity) {
    const r = await api("PUT", `/api/admin/entities/${e.id}`, { legalName: e.legalName, isActive: e.isActive });
    setMsg(r.ok ? { kind: "ok", text: `${e.name} salvato.` } : { kind: "error", text: errorText(r) });
  }
  const set = (id: number, patch: Partial<Entity>) => setRows(rows.map((r) => (r.id === id ? { ...r, ...patch } : r)));

  return (
    <div className="flex flex-col gap-4">
      <p className="text-slate-600 dark:text-slate-300">
        I destinatari che i trasportatori scelgono. Un destinatario disattivato non è più prenotabile; le prenotazioni esistenti restano.
      </p>
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <ul className="flex flex-col gap-3">
        {rows.map((e) => (
          <li key={e.id} className="flex flex-col gap-3 rounded-2xl border border-slate-200 p-4 sm:flex-row sm:items-end dark:border-slate-800">
            <div className="sm:w-48">
              <div className="font-semibold">
                {e.code} · {e.name}
              </div>
              <div className="text-sm text-slate-500">{siteNames[e.siteId]}</div>
            </div>
            <label className="flex flex-1 flex-col gap-1 text-sm font-medium">
              Ragione sociale
              <input value={e.legalName ?? ""} maxLength={200} onChange={(ev) => set(e.id, { legalName: ev.target.value })} className={inputClass} />
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={e.isActive} onChange={(ev) => set(e.id, { isActive: ev.target.checked })} className="h-5 w-5 accent-blue-700" />
              Prenotabile
            </label>
            <Button variant="secondary" onClick={() => save(e)}>
              Salva
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}
