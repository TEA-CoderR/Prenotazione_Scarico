"use client";

export type ApiResult<T> = { ok: true; data: T } | { ok: false; status: number; error: string; message?: string };

/** JSON request to our own API; never throws. */
export async function api<T = unknown>(method: string, url: string, body?: unknown): Promise<ApiResult<T>> {
  try {
    const res = await fetch(url, {
      method,
      cache: "no-store",
      headers: body !== undefined || method !== "GET" ? { "content-type": "application/json" } : undefined,
      body: body !== undefined ? JSON.stringify(body) : method !== "GET" ? "{}" : undefined,
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok) return { ok: true, data: data as T };
    return { ok: false, status: res.status, error: data.error ?? "GENERIC", message: data.message };
  } catch {
    return { ok: false, status: 0, error: "NETWORK" };
  }
}

const MESSAGES: Record<string, string> = {
  VALIDATION: "Dati non validi",
  FORBIDDEN: "Operazione non consentita per il suo ruolo",
  CONFLICT: "Operazione non possibile",
  NOT_FOUND: "Elemento non trovato",
  NETWORK: "Connessione non riuscita",
};

/** Human-readable Italian message, with the server's detail when useful. */
export function errorText(r: { error: string; message?: string }): string {
  const base = MESSAGES[r.error] ?? "Operazione non riuscita";
  const detail = r.message && r.error !== "FORBIDDEN" ? `: ${translate(r.message)}` : "";
  return base + detail;
}

// Server messages are English; translate the ones staff will meet.
const DETAILS: [RegExp, string][] = [
  [/There must always be at least one active administrator/, "deve restare almeno un amministratore attivo"],
  [/You cannot remove your own administrator access/, "non può togliere a sé stesso l'accesso da amministratore"],
  [/This e-mail already has an account/, "esiste già un account con questa e-mail"],
  [/Choose at least one warehouse/, "scelga almeno un magazzino"],
  [/Already closed on that day/, "il magazzino è già chiuso in quel giorno"],
  [/The date is in the past/, "la data è nel passato"],
  [/Only your own warehouses/, "solo per i suoi magazzini"],
  [/closing must be after opening/, "l'orario di chiusura deve essere dopo l'apertura"],
  [/time ranges overlap/, "due fasce orarie si sovrappongono"],
  [/quarter hour/, "usi orari ai quarti d'ora (es. 09:00, 13:30)"],
];

function translate(msg: string): string {
  const hit = DETAILS.find(([re]) => re.test(msg));
  return hit ? hit[1] : msg.split("\n")[0].replace(/^✖\s*/, "");
}
