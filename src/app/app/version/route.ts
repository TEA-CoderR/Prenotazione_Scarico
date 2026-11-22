import { connection } from "next/server";

// GET /app/version → the latest store scanner app: { versionCode, versionName, sha256, size, notes, url }.
// The scanner app asks this to update itself (download <site>/app, check the sha256, install).
// version.json is published next to the APK in the GitHub release "scanner-latest" (.github/workflows/scanner.yml).
const SRC = "https://github.com/TEA-CoderR/Prenotazione_Scarico/releases/download/scanner-latest/version.json";
const MAX_AGE = 5 * 60_000;

let cache: { at: number; body: Record<string, unknown> | null } | null = null;

export async function GET() {
  await connection();
  if (!cache || Date.now() - cache.at > MAX_AGE) {
    try {
      const r = await fetch(SRC, { cache: "no-store", redirect: "follow", signal: AbortSignal.timeout(10_000) });
      if (r.ok) cache = { at: Date.now(), body: (await r.json()) as Record<string, unknown> };
      else if (r.status === 404) cache = { at: Date.now(), body: null };
    } catch {
      // GitHub not reachable: keep the last answer (if any)
    }
  }
  if (!cache?.body) return Response.json({ error: "NO_RELEASE" }, { status: 404, headers: { "Cache-Control": "no-cache" } });
  return Response.json({ ...cache.body, url: "/app" }, { headers: { "Cache-Control": "no-cache" } });
}
