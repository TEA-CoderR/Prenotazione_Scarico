"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button, ErrorNote, Field, inputClass } from "./ui";

export function StaffLogin() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      if (res.ok) {
        router.replace("/magazzino");
        return;
      }
      setError(
        res.status === 429
          ? "Troppi tentativi. Riprovi tra 15 minuti."
          : res.status === 401
            ? "E-mail o password non corretti."
            : "Accesso non riuscito. Riprovi.",
      );
    } catch {
      setError("Connessione non riuscita. Riprovi.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="mx-auto flex w-full max-w-sm flex-col gap-4">
      <h1 className="text-2xl font-semibold">Accesso magazzino</h1>
      <Field label="E-mail">
        {(id) => (
          <input id={id} type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} className={inputClass} />
        )}
      </Field>
      <Field label="Password">
        {(id) => (
          <input
            id={id}
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className={inputClass}
          />
        )}
      </Field>
      {error && <ErrorNote>{error}</ErrorNote>}
      <Button type="submit" disabled={busy}>
        {busy ? "Accesso in corso…" : "Accedi"}
      </Button>
    </form>
  );
}
