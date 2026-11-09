// Cloudflare Turnstile server-side check. Tokens are single-use and valid for 300 s.
// Without TURNSTILE_SECRET_KEY (local development) the check is skipped.

export const SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

export type CaptchaResult = { ok: true; skipped?: boolean } | { ok: false; codes: string[] };

export async function verifyCaptcha(
  token: unknown,
  opts: { secret?: string; remoteIp?: string; fetchImpl?: typeof fetch; timeoutMs?: number },
): Promise<CaptchaResult> {
  if (!opts.secret) return { ok: true, skipped: true };
  if (typeof token !== "string" || !token || token.length > 2048) return { ok: false, codes: ["missing-input-response"] };

  const body = new URLSearchParams({ secret: opts.secret, response: token });
  if (opts.remoteIp && opts.remoteIp !== "unknown") body.set("remoteip", opts.remoteIp);
  try {
    const res = await (opts.fetchImpl ?? fetch)(SITEVERIFY_URL, {
      method: "POST",
      body,
      signal: AbortSignal.timeout(opts.timeoutMs ?? 8000),
    });
    const data = (await res.json()) as { success?: boolean; "error-codes"?: string[] };
    return data.success === true ? { ok: true } : { ok: false, codes: data["error-codes"] ?? ["unknown"] };
  } catch (e) {
    // Fail closed: if Cloudflare cannot be reached, the request is refused (carriers can retry or phone).
    console.error(`[captcha] siteverify unreachable: ${e instanceof Error ? e.message : String(e)}`);
    return { ok: false, codes: ["siteverify-unreachable"] };
  }
}
