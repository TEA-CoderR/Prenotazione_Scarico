import { describe, expect, it } from "vitest";
import { renderBookingMail } from "@/lib/mail/templates";

describe("booking mail", () => {
  const data = {
    code: "P261110-ABCDE",
    entityName: "SAN FILIPPO",
    siteName: "Magazzino BMS / San Filippo",
    siteAddress: null,
    date: "2026-11-10",
    time: "10:00",
    manageUrl: "https://prenota.casa-te.com/b/tok",
  };

  it("renders Italian and English versions with the code in the subject", () => {
    const it_ = renderBookingMail("requested", "it", data);
    expect(it_.subject).toBe("Richiesta di scarico ricevuta – P261110-ABCDE");
    expect(it_.text).toContain("2026-11-10 10:00");
    expect(renderBookingMail("confirmed", "en", data).subject).toContain("Unloading confirmed");
  });

  it("escapes HTML", () => {
    const m = renderBookingMail("requested", "it", { ...data, siteName: "<b>x</b>" });
    expect(m.html).toContain("&lt;b&gt;x&lt;/b&gt;");
  });
});
