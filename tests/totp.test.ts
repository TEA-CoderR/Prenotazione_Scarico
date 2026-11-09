import { describe, expect, it } from "vitest";
import { base32Decode, base32Encode, generateSecret, hotp, otpauthUrl, totpCode, verifyTotp } from "@/lib/auth/totp";

// RFC 6238 Appendix B test key (ASCII "12345678901234567890") with SHA-1.
const RFC_KEY = Buffer.from("12345678901234567890");
const RFC_SECRET = base32Encode(RFC_KEY);

describe("TOTP", () => {
  it("round-trips base32", () => {
    expect(RFC_SECRET).toBe("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    expect(base32Decode(RFC_SECRET).equals(RFC_KEY)).toBe(true);
    expect(base32Decode(generateSecret())).toHaveLength(20);
  });

  it("matches the RFC 6238 test vectors (last 6 digits)", () => {
    expect(hotp(RFC_KEY, Math.floor(59 / 30), 8)).toBe("94287082");
    expect(totpCode(RFC_SECRET, new Date(59_000))).toBe("287082");
    expect(totpCode(RFC_SECRET, new Date(1111111109_000))).toBe("081804");
    expect(totpCode(RFC_SECRET, new Date(2000000000_000))).toBe("279037");
  });

  it("accepts one step of clock drift and refuses reuse", () => {
    const now = new Date(1_800_000_000_000);
    const prev = totpCode(RFC_SECRET, new Date(now.getTime() - 30_000));
    const step = verifyTotp(RFC_SECRET, prev, now);
    expect(step).not.toBeNull();
    expect(verifyTotp(RFC_SECRET, prev, now, step)).toBeNull(); // already used
    expect(verifyTotp(RFC_SECRET, totpCode(RFC_SECRET, new Date(now.getTime() - 90_000)), now)).toBeNull(); // too old
    expect(verifyTotp(RFC_SECRET, "12345", now)).toBeNull();
    expect(verifyTotp(RFC_SECRET, " " + totpCode(RFC_SECRET, now).replace(/(\d{3})/, "$1 "), now)).not.toBeNull();
  });

  it("builds an otpauth URL for authenticator apps", () => {
    const url = otpauthUrl("ABC", "m.rossi@casa-te.com");
    expect(url).toBe(
      "otpauth://totp/Prenotazione%20Scarico%3Am.rossi%40casa-te.com?secret=ABC&issuer=Prenotazione+Scarico&algorithm=SHA1&digits=6&period=30",
    );
  });
});
