"use client";

import { useEffect, useState } from "react";
import { Notice } from "./admin-console";
import { api, errorText } from "./api";

interface Entry {
  id: number;
  at: string;
  userName: string | null;
  action: string;
  target: string | null;
}

const ACTIONS: Record<string, string> = {
  "site.update": "Modifica magazzino",
  "entity.update": "Modifica destinatario",
  "slot_rules.update": "Modifica durate",
  "closure.add": "Nuova chiusura",
  "closure.delete": "Chiusura rimossa",
  "user.create": "Nuovo utente",
  "user.update": "Modifica utente",
  "user.reset_password": "Nuova password",
  "user.reset_2fa": "Azzerata verifica 2 passaggi",
  "user.change_password": "Password cambiata dall'utente",
};

export function AuditTab() {
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api<{ entries: Entry[] }>("GET", "/api/admin/audit").then((r) => (r.ok ? setEntries(r.data.entries) : setError(errorText(r))));
  }, []);
  if (error) return <Notice kind="error">{error}</Notice>;
  if (!entries) return <p className="text-slate-500">Caricamento…</p>;
  const fmt = new Intl.DateTimeFormat("it-IT", { dateStyle: "short", timeStyle: "short", timeZone: "Europe/Rome" });
  return (
    <div className="flex flex-col gap-2">
      <p className="text-slate-600 dark:text-slate-300">Ultime 100 modifiche alle impostazioni. Le operazioni sulle prenotazioni sono registrate sulla prenotazione stessa.</p>
      {entries.length === 0 && <p className="text-slate-500">Nessuna modifica registrata.</p>}
      <ul className="divide-y divide-slate-200 rounded-xl border border-slate-200 text-sm dark:divide-slate-800 dark:border-slate-800">
        {entries.map((e) => (
          <li key={e.id} className="flex flex-wrap gap-x-4 px-4 py-2">
            <span className="w-32 shrink-0 tabular-nums text-slate-500">{fmt.format(new Date(e.at))}</span>
            <span className="w-32 shrink-0">{e.userName ?? "—"}</span>
            <span className="font-medium">{ACTIONS[e.action] ?? e.action}</span>
            <span className="text-slate-500">{e.target}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
