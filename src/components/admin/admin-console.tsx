"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "./api";
import { AuditTab } from "./audit-tab";
import { ClosuresTab } from "./closures-tab";
import { EntitiesTab } from "./entities-tab";
import { ReportTab } from "./report-tab";
import { RulesTab } from "./rules-tab";
import { SitesTab } from "./sites-tab";
import { UsersTab } from "./users-tab";

export interface MeSite {
  id: number;
  code: string;
  name: string;
}
interface Me {
  user: { name: string; role: "warehouse_clerk" | "warehouse_manager" | "admin" };
  sites: MeSite[];
}

const TABS = [
  ["report", "Report"],
  ["sites", "Magazzini"],
  ["closures", "Chiusure"],
  ["rules", "Durate"],
  ["entities", "Destinatari"],
  ["users", "Utenti"],
  ["audit", "Registro"],
] as const;
type Tab = (typeof TABS)[number][0];

export function AdminConsole() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [tab, setTab] = useState<Tab>("report");

  useEffect(() => {
    api<Me>("GET", "/api/staff/me").then((r) => {
      if (!r.ok && r.status === 401) return router.replace("/magazzino/login");
      if (!r.ok && r.status === 403) return router.replace("/magazzino/2fa");
      if (!r.ok) return;
      if (r.data.user.role === "warehouse_clerk") return router.replace("/magazzino");
      setMe(r.data);
    });
  }, [router]);

  if (!me) return <p className="text-slate-500">Caricamento…</p>;
  const isAdmin = me.user.role === "admin";
  const tabs = isAdmin ? TABS : TABS.filter(([k]) => k === "report" || k === "closures");

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">{isAdmin ? "Amministrazione" : "Report e chiusure"}</h1>
        <Link href="/magazzino" className="text-sm text-blue-700 underline dark:text-blue-400">
          ← Torna alle prenotazioni
        </Link>
      </div>
      {tabs.length > 1 && (
        <div className="flex gap-1 overflow-x-auto rounded-xl bg-slate-100 p-1 dark:bg-slate-900" role="tablist">
          {tabs.map(([key, label]) => (
            <button
              key={key}
              role="tab"
              aria-selected={tab === key}
              onClick={() => setTab(key)}
              className={`min-h-10 shrink-0 rounded-lg px-4 font-semibold ${tab === key ? "bg-white shadow-sm dark:bg-slate-800" : "text-slate-600 dark:text-slate-400"}`}
            >
              {label}
            </button>
          ))}
        </div>
      )}
      {tab === "report" && <ReportTab sites={me.sites} isAdmin={isAdmin} />}
      {tab === "sites" && <SitesTab />}
      {tab === "closures" && <ClosuresTab sites={me.sites} isAdmin={isAdmin} />}
      {tab === "rules" && <RulesTab />}
      {tab === "entities" && <EntitiesTab />}
      {tab === "users" && <UsersTab />}
      {tab === "audit" && <AuditTab />}
    </div>
  );
}

/** Shared bits for the tabs. */
export function Notice({ kind, children }: { kind: "ok" | "warn" | "error"; children: React.ReactNode }) {
  const style = {
    ok: "border-emerald-200 bg-emerald-50 text-emerald-900 dark:border-emerald-900 dark:bg-emerald-950/50 dark:text-emerald-100",
    warn: "border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/50 dark:text-amber-100",
    error: "border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950/50 dark:text-red-200",
  }[kind];
  return (
    <div role={kind === "error" ? "alert" : "status"} className={`rounded-xl border px-4 py-3 ${style}`}>
      {children}
    </div>
  );
}

export const WEEKDAYS = ["Lunedì", "Martedì", "Mercoledì", "Giovedì", "Venerdì", "Sabato", "Domenica"];
export const ROLE_LABEL = { warehouse_clerk: "Magazziniere", warehouse_manager: "Capo magazzino", admin: "Amministratore" } as const;
