import { describe, expect, it, vi } from "vitest";
import { SITEVERIFY_URL, verifyCaptcha } from "@/lib/captcha";

const ok = (body: unknown) => vi.fn(async () => new Response(JSON.stringify(body), { headers: { "content-type": "application/json" } }));

describe("verifyCaptcha", () => {
  it("is skipped when no secret is configured (development)", async () => {
    expect(await verifyCaptcha(undefined, {})).toEqual({ ok: true, skipped: true });
  });

  it("rejects a missing token without calling Cloudflare", async () => {
    const f = ok({ success: true });
    expect(await verifyCaptcha("", { secret: "s", fetchImpl: f })).toEqual({ ok: false, codes: ["missing-input-response"] });
    expect(f).not.toHaveBeenCalled();
  });

  it("posts secret, token and client IP to siteverify", async () => {
    const f = ok({ success: true });
    expect(await verifyCaptcha("tok", { secret: "sec", remoteIp: "1.2.3.4", fetchImpl: f })).toEqual({ ok: true });
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(SITEVERIFY_URL);
    const body = init.body as URLSearchParams;
    expect(body.get("secret")).toBe("sec");
    expect(body.get("response")).toBe("tok");
    expect(body.get("remoteip")).toBe("1.2.3.4");
  });

  it("returns Cloudflare's error codes on failure", async () => {
    const f = ok({ success: false, "error-codes": ["timeout-or-duplicate"] });
    expect(await verifyCaptcha("tok", { secret: "sec", fetchImpl: f })).toEqual({ ok: false, codes: ["timeout-or-duplicate"] });
  });

  it("fails closed when Cloudflare is unreachable", async () => {
    const f = vi.fn(async () => {
      throw new Error("ECONNREFUSED");
    });
    vi.spyOn(console, "error").mockImplementation(() => {});
    expect(await verifyCaptcha("tok", { secret: "sec", fetchImpl: f as unknown as typeof fetch })).toEqual({
      ok: false,
      codes: ["siteverify-unreachable"],
    });
  });
});
