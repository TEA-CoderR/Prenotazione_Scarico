"use client";

import { useCallback, useEffect, useState } from "react";
import { Button, Field, inputClass } from "../ui";
import { Notice, ROLE_LABEL } from "./admin-console";
import { api, errorText } from "./api";

type Role = keyof typeof ROLE_LABEL;
interface User {
  id: number;
  email: string;
  name: string;
  role: Role;
  isActive: boolean;
  hasTotp: boolean;
  siteIds: number[];
}
interface Draft {
  email: string;
  name: string;
  role: Role;
  siteIds: number[];
  isActive: boolean;
}
const EMPTY: Draft = { email: "", name: "", role: "warehouse_clerk", siteIds: [], isActive: true };

export function UsersTab() {
  const [users, setUsers] = useState<User[] | null>(null);
  const [sites, setSites] = useState<{ id: number; name: string }[]>([]);
  const [editing, setEditing] = useState<number | "new" | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [msg, setMsg] = useState<{ kind: "ok" | "warn" | "error"; text: React.ReactNode } | null>(null);
  const [confirm, setConfirm] = useState<{ id: number; action: "reset-password" | "reset-2fa" } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const [u, s] = await Promise.all([api<{ users: User[] }>("GET", "/api/admin/users"), api<{ sites: { id: number; name: string }[] }>("GET", "/api/admin/settings")]);
    if (!u.ok) return setMsg({ kind: "error", text: errorText(u) });
    setUsers(u.data.users);
    if (s.ok) setSites(s.data.sites);
  }, []);
  useEffect(() => {
    load(); // eslint-disable-line react-hooks/set-state-in-effect -- initial fetch
  }, [load]);

  const siteName = (id: number) => sites.find((s) => s.id === id)?.name ?? `#${id}`;

  function passwordNotice(name: string, password: string) {
    return {
      kind: "warn" as const,
      text: (
        <>
          <p>
            Password temporanea per <b>{name}</b> (visibile solo ora): la comunichi di persona o al telefono, non per e-mail.
          </p>
          <p className="mt-2 select-all font-mono text-xl" data-testid="temp-password">
            {password}
          </p>
          <p className="mt-1 text-sm">Al primo accesso l&apos;utente attiverà anche l&apos;app di autenticazione.</p>
        </>
      ),
    };
  }

  async function save() {
    setBusy(true);
    setMsg(null);
    const body = { name: draft.name, role: draft.role, siteIds: draft.role === "admin" ? [] : draft.siteIds, isActive: draft.isActive };
    const r =
      editing === "new"
        ? await api<{ password: string }>("POST", "/api/admin/users", { ...body, email: draft.email })
        : await api("PUT", `/api/admin/users/${editing}`, body);
    setBusy(false);
    if (!r.ok) return setMsg({ kind: "error", text: errorText(r) });
    setMsg(editing === "new" ? passwordNotice(draft.name, (r.data as { password: string }).password) : { kind: "ok", text: "Utente aggiornato." });
    setEditing(null);
    await load();
  }

  async function runAction() {
    if (!confirm) return;
    const u = users!.find((x) => x.id === confirm.id)!;
    const r = await api<{ password?: string }>("POST", `/api/admin/users/${confirm.id}`, { action: confirm.action });
    setConfirm(null);
    if (!r.ok) return setMsg({ kind: "error", text: errorText(r) });
    setMsg(
      confirm.action === "reset-password"
        ? passwordNotice(u.name, r.data.password!)
        : { kind: "ok", text: `Verifica in due passaggi azzerata per ${u.name}: al prossimo accesso la riconfigurerà.` },
    );
    await load();
  }

  if (!users) return msg ? <Notice kind="error">{msg.text}</Notice> : <p className="text-slate-500">Caricamento…</p>;

  const form = (
    <div className="flex flex-col gap-4 rounded-2xl border-2 border-blue-700 p-4">
      <h2 className="text-lg font-semibold">{editing === "new" ? "Nuovo utente" : "Modifica utente"}</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        {editing === "new" && (
          <Field label="E-mail">
            {(id) => <input id={id} type="email" value={draft.email} onChange={(e) => setDraft({ ...draft, email: e.target.value })} className={inputClass} />}
          </Field>
        )}
        <Field label="Nome e cognome">
          {(id) => <input id={id} value={draft.name} maxLength={120} onChange={(e) => setDraft({ ...draft, name: e.target.value })} className={inputClass} />}
        </Field>
        <Field label="Ruolo">
          {(id) => (
            <select id={id} value={draft.role} onChange={(e) => setDraft({ ...draft, role: e.target.value as Role })} className={inputClass}>
              {(Object.keys(ROLE_LABEL) as Role[]).map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABEL[r]}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      <p className="-mt-2 text-sm text-slate-500">
        Magazziniere: conferma e gestisce gli scarichi. Capo magazzino: inoltre annulla prenotazioni e gestisce le chiusure. Amministratore: tutto, su tutti i magazzini.
      </p>
      {draft.role !== "admin" && (
        <fieldset className="flex flex-col gap-1">
          <legend className="mb-1 text-sm font-medium">Magazzini</legend>
          {sites.map((s) => (
            <label key={s.id} className="flex items-center gap-2">
              <input
                type="checkbox"
                className="h-5 w-5 accent-blue-700"
                checked={draft.siteIds.includes(s.id)}
                onChange={(e) => setDraft({ ...draft, siteIds: e.target.checked ? [...draft.siteIds, s.id] : draft.siteIds.filter((x) => x !== s.id) })}
              />
              {s.name}
            </label>
          ))}
        </fieldset>
      )}
      {editing !== "new" && (
        <label className="flex items-center gap-2">
          <input type="checkbox" className="h-5 w-5 accent-blue-700" checked={draft.isActive} onChange={(e) => setDraft({ ...draft, isActive: e.target.checked })} />
          Account attivo
        </label>
      )}
      <div className="flex gap-2">
        <Button variant="secondary" onClick={() => setEditing(null)}>
          Annulla
        </Button>
        <Button onClick={save} disabled={busy || !draft.name.trim() || (editing === "new" && !draft.email.trim())}>
          {editing === "new" ? "Crea utente" : "Salva"}
        </Button>
      </div>
    </div>
  );

  return (
    <div className="flex flex-col gap-4">
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      {editing === null && (
        <Button
          className="self-start"
          onClick={() => {
            setDraft(EMPTY);
            setEditing("new");
            setMsg(null);
          }}
        >
          + Nuovo utente
        </Button>
      )}
      {editing === "new" && form}
      <ul className="flex flex-col gap-3">
        {users.map((u) =>
          editing === u.id ? (
            <li key={u.id}>{form}</li>
          ) : (
            <li key={u.id} className={`rounded-2xl border border-slate-200 p-4 dark:border-slate-800 ${u.isActive ? "" : "opacity-60"}`}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <div className="font-semibold">
                    {u.name} {!u.isActive && <span className="text-sm font-normal text-slate-500">(disattivato)</span>}
                  </div>
                  <div className="text-sm text-slate-500">{u.email}</div>
                  <div className="mt-1 text-sm">
                    {ROLE_LABEL[u.role]} · {u.role === "admin" ? "tutti i magazzini" : u.siteIds.map(siteName).join(", ")}
                  </div>
                </div>
                <span className={`rounded-full px-3 py-1 text-xs font-semibold ${u.hasTotp ? "bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200" : "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200"}`}>
                  {u.hasTotp ? "Verifica in 2 passaggi attiva" : "2 passaggi da attivare"}
                </span>
              </div>
              {confirm?.id === u.id ? (
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <span className="text-sm">
                    {confirm.action === "reset-password" ? "Generare una nuova password? L'utente verrà disconnesso." : "Azzerare l'app di autenticazione (es. telefono perso)?"}
                  </span>
                  <Button variant="secondary" onClick={() => setConfirm(null)}>
                    No
                  </Button>
                  <Button variant="danger" onClick={runAction}>
                    Sì, procedi
                  </Button>
                </div>
              ) : (
                <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-sm">
                  <button
                    className="text-blue-700 underline dark:text-blue-400"
                    onClick={() => {
                      setDraft({ email: u.email, name: u.name, role: u.role, siteIds: u.siteIds, isActive: u.isActive });
                      setEditing(u.id);
                      setMsg(null);
                    }}
                  >
                    Modifica
                  </button>
                  <button className="text-blue-700 underline dark:text-blue-400" onClick={() => setConfirm({ id: u.id, action: "reset-password" })}>
                    Nuova password
                  </button>
                  {u.hasTotp && (
                    <button className="text-blue-700 underline dark:text-blue-400" onClick={() => setConfirm({ id: u.id, action: "reset-2fa" })}>
                      Azzera verifica in 2 passaggi
                    </button>
                  )}
                </div>
              )}
            </li>
          ),
        )}
      </ul>
    </div>
  );
}
