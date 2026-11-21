import { describe, expect, it, vi } from "vitest";

vi.mock("next/server", async (orig) => ({ ...(await orig<typeof import("next/server")>()), connection: async () => {} }));

describe("purchasing program key", () => {
  it("is off without a key, and needs the exact key", async () => {
    vi.resetModules();
    process.env.DATABASE_URL ??= "postgres://x";
    delete process.env.ERP_API_TOKEN;
    let { requireErp } = await import("@/app/api/erp/auth");
    expect((await requireErp(new Request("http://x")))?.status).toBe(503);

    vi.resetModules();
    process.env.ERP_API_TOKEN = "k".repeat(40);
    ({ requireErp } = await import("@/app/api/erp/auth"));
    const req = (h?: string) => new Request("http://x", { headers: h ? { authorization: h } : {} });
    expect((await requireErp(req()))?.status).toBe(401);
    expect((await requireErp(req("Bearer wrong")))?.status).toBe(401);
    expect(await requireErp(req(`Bearer ${"k".repeat(40)}`))).toBeNull();
    delete process.env.ERP_API_TOKEN;
  });
});
