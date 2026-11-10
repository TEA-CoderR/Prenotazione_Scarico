"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { Button, ErrorNote, Field, inputClass } from "./ui";

export function ChangePassword() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  const mismatch = repeat.length > 0 && next !== repeat;
  const tooShort = next.length > 0 && next.length < 10;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (mismatch || tooShort) return;
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/auth/password", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ current, next }),
      });
      const body = await res.json().catch(() => ({}));
      if (res.ok) {
        setDone(true);
        return;
      }
      setError(
        res.status === 401
          ? "Sessione scaduta: acceda di nuovo."
          : res.status === 429
            ? "Troppi tentativi. Riprovi tra 15 minuti."
            : body.error === "FORBIDDEN"
              ? "La password attuale non è corretta."
              : body.message ?? "Password non valida.",
      );
    } catch {
      setError("Connessione non riuscita. Riprovi.");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="mx-auto flex w-full max-w-sm flex-col gap-4">
        <h1 className="text-2xl font-semibold">Password cambiata</h1>
        <p className="text-slate-600 dark:text-slate-300">Gli altri dispositivi sono stati disconnessi; su questo resta collegato.</p>
        <Link href="/magazzino" className="text-blue-700 underline dark:text-blue-400">
          ← Torna alle prenotazioni
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="mx-auto flex w-full max-w-sm flex-col gap-4">
      <h1 className="text-2xl font-semibold">Cambia password</h1>
      <Field label="Password attuale">
        {(id) => <input id={id} type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} className={inputClass} />}
      </Field>
      <Field label="Nuova password" hint="almeno 10 caratteri" error={tooShort ? "Almeno 10 caratteri" : null}>
        {(id) => <input id={id} type="password" autoComplete="new-password" required value={next} onChange={(e) => setNext(e.target.value)} className={inputClass} />}
      </Field>
      <Field label="Ripeta la nuova password" error={mismatch ? "Le due password non coincidono" : null}>
        {(id) => <input id={id} type="password" autoComplete="new-password" required value={repeat} onChange={(e) => setRepeat(e.target.value)} className={inputClass} />}
      </Field>
      {error && <ErrorNote>{error}</ErrorNote>}
      <Button type="submit" disabled={busy || mismatch || tooShort || !current || !next}>
        Cambia password
      </Button>
      <Link href="/magazzino" className="text-sm text-blue-700 underline dark:text-blue-400">
        Annulla
      </Link>
    </form>
  );
}
