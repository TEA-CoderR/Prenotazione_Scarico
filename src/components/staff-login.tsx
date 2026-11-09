"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button, ErrorNote, Field, inputClass } from "./ui";

export function StaffLogin() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [needCode, setNeedCode] = useState(false);
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
        body: JSON.stringify({ email, password, code: needCode ? code : undefined }),
      });
      const body = await res.json().catch(() => ({}));
      if (res.ok) {
        router.replace(body.mfaSetupRequired ? "/magazzino/2fa" : "/magazzino");
        return;
      }
      if (body.error === "TOTP_REQUIRED") {
        setNeedCode(true);
        return;
      }
      setError(
        res.status === 429
          ? "Troppi tentativi. Riprovi tra 15 minuti."
          : body.error === "BAD_TOTP"
            ? "Codice non valido o già usato. Attenda il codice successivo nell'app."
            : res.status === 401
              ? "E-mail o password non corretti."
              : "Accesso non riuscito. Riprovi.",
      );
      if (body.error === "BAD_TOTP") setCode("");
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
          <input id={id} type="email" autoComplete="username" required readOnly={needCode} value={email} onChange={(e) => setEmail(e.target.value)} className={inputClass} />
        )}
      </Field>
      <Field label="Password">
        {(id) => (
          <input
            id={id}
            type="password"
            autoComplete="current-password"
            required
            readOnly={needCode}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className={inputClass}
          />
        )}
      </Field>
      {needCode && (
        <Field label="Codice dell'app di autenticazione" hint="6 cifre">
          {(id) => (
            <input
              id={id}
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9 ]{6,7}"
              maxLength={7}
              required
              autoFocus
              value={code}
              onChange={(e) => setCode(e.target.value)}
              className={`${inputClass} text-center font-mono text-2xl tracking-[0.3em]`}
            />
          )}
        </Field>
      )}
      {error && <ErrorNote>{error}</ErrorNote>}
      <Button type="submit" disabled={busy}>
        {busy ? "Accesso in corso…" : needCode ? "Verifica" : "Accedi"}
      </Button>
    </form>
  );
}
