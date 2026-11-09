"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { Button, ErrorNote, Field, inputClass } from "./ui";

export function TotpSetup() {
  const router = useRouter();
  const [setup, setSetup] = useState<{ secret: string; qrSvg: string } | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    fetch("/api/auth/2fa", { cache: "no-store" })
      .then(async (res) => {
        if (res.status === 401) return router.replace("/magazzino/login");
        if (res.status === 409) return router.replace("/magazzino");
        if (!res.ok) throw new Error();
        setSetup(await res.json());
      })
      .catch(() => setError("Impossibile preparare la configurazione. Ricarichi la pagina."));
  }, [router]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/auth/2fa", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ code }),
      });
      if (res.ok) return router.replace("/magazzino");
      setError(res.status === 429 ? "Troppi tentativi. Riprovi tra 15 minuti." : "Codice non corretto. Controlli l'ora del telefono e riprovi.");
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="mx-auto flex w-full max-w-md flex-col gap-4">
      <h1 className="text-2xl font-semibold">Attivi la verifica in due passaggi</h1>
      <p className="text-slate-600 dark:text-slate-300">
        Per proteggere l&apos;accesso serve un codice dal telefono oltre alla password. Va fatto una sola volta.
      </p>
      <ol className="list-decimal space-y-1 pl-5 text-slate-700 dark:text-slate-200">
        <li>Installi sul telefono Google Authenticator o Microsoft Authenticator.</li>
        <li>Nell&apos;app tocchi «+» e inquadri il codice qui sotto.</li>
        <li>Scriva il codice a 6 cifre che appare nell&apos;app.</li>
      </ol>
      {setup ? (
        <div className="flex flex-col items-center gap-2 rounded-2xl border border-slate-200 p-4 dark:border-slate-800">
          {/* QR generated server-side by the qrcode library from our own otpauth URL. */}
          <div className="w-52 rounded-lg bg-white p-2" dangerouslySetInnerHTML={{ __html: setup.qrSvg }} />
          <details className="text-center text-sm text-slate-500">
            <summary className="cursor-pointer">Non riesce a inquadrarlo? Inserisca la chiave a mano</summary>
            <code className="mt-2 block break-all font-mono text-base text-slate-800 dark:text-slate-100" data-testid="totp-secret">
              {setup.secret.match(/.{1,4}/g)?.join(" ")}
            </code>
          </details>
        </div>
      ) : (
        !error && <p className="text-slate-500">Caricamento…</p>
      )}
      <Field label="Codice a 6 cifre">
        {(id) => (
          <input
            id={id}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={7}
            required
            value={code}
            onChange={(e) => setCode(e.target.value)}
            className={`${inputClass} text-center font-mono text-2xl tracking-[0.3em]`}
          />
        )}
      </Field>
      {error && <ErrorNote>{error}</ErrorNote>}
      <Button type="submit" disabled={busy || !setup}>
        Attiva
      </Button>
    </form>
  );
}
