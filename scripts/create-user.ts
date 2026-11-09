// Creates a staff account, or resets its password/role/sites if the e-mail exists.
//
//   tsx scripts/create-user.ts --email m.rossi@casa-te.com --name "Mario Rossi" \
//       --role warehouse_manager --sites FOIANO
//   (in Docker: docker compose exec app node dist-scripts/create-user.js ...)
//
// Roles: warehouse_clerk | warehouse_manager | admin. --sites takes site codes
// (BMS-SFILIPPO, FOIANO, MARLIA, SANMARCO, SANCONCORDIO); admins see all sites.
// The password comes from NEW_USER_PASSWORD, or a random one is generated and printed once.
import { randomBytes } from "node:crypto";
import { parseArgs } from "node:util";
import { eq, inArray } from "drizzle-orm";
import { createDb } from "../src/db/client";
import { appUser, site, staffSession, userRole, userSite, type UserRole } from "../src/db/schema";
import { hashPassword, passwordProblem } from "../src/lib/auth/password";

async function main() {
  const { values } = parseArgs({
    options: {
      email: { type: "string" },
      name: { type: "string" },
      role: { type: "string" },
      sites: { type: "string", default: "" },
    },
  });
  const email = values.email?.trim().toLowerCase();
  const role = values.role as UserRole;
  if (!email || !values.name || !userRole.enumValues.includes(role)) {
    throw new Error("Usage: --email <e-mail> --name <name> --role <warehouse_clerk|warehouse_manager|admin> [--sites CODE,CODE]");
  }
  const siteCodes = values.sites.split(",").map((s) => s.trim().toUpperCase()).filter(Boolean);
  if (role !== "admin" && !siteCodes.length) throw new Error("Warehouse users need --sites");

  const generated = !process.env.NEW_USER_PASSWORD;
  const password = process.env.NEW_USER_PASSWORD ?? randomBytes(12).toString("base64url");
  const problem = passwordProblem(password);
  if (problem) throw new Error(problem);

  const url = process.env.DATABASE_URL;
  if (!url) throw new Error("DATABASE_URL is not set");
  const { db, pool } = createDb(url);
  try {
    const sites = siteCodes.length ? await db.select().from(site).where(inArray(site.code, siteCodes)) : [];
    const missing = siteCodes.filter((c) => !sites.some((s) => s.code === c));
    if (missing.length) throw new Error(`Unknown site code(s): ${missing.join(", ")}`);

    const passwordHash = await hashPassword(password);
    await db.transaction(async (tx) => {
      const [u] = await tx
        .insert(appUser)
        .values({ email, name: values.name!, role, passwordHash })
        .onConflictDoUpdate({
          target: appUser.email,
          // Re-running the command is also the recovery path: new password and 2FA set up again.
          set: { name: values.name!, role, passwordHash, isActive: true, totpSecret: null, totpPendingSecret: null, totpLastStep: null },
        })
        .returning({ id: appUser.id });
      await tx.delete(userSite).where(eq(userSite.userId, u.id));
      if (sites.length) await tx.insert(userSite).values(sites.map((s) => ({ userId: u.id, siteId: s.id })));
      await tx.delete(staffSession).where(eq(staffSession.userId, u.id)); // sign out everywhere
    });
    console.info(`User ${email} (${role}) ready${sites.length ? ` for ${siteCodes.join(", ")}` : ""}.`);
    if (generated) console.info(`Temporary password: ${password}`);
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
