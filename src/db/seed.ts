import { sql } from "drizzle-orm";
import { italianHolidays } from "@/lib/holidays";
import { DEFAULT_SLOT_RULES } from "@/lib/scheduling/duration";
import type { Db } from "./client";
import { bookingPolicy, closure, entity, site, slotRule } from "./schema";

// Five physical sites. Entities 0 (BMS) and 3 (SAN FILIPPO) share the first one.
// Addresses and legal names are still to be provided (see design doc).
export const SITES = [
  { code: "BMS-SFILIPPO", name: "Magazzino BMS / San Filippo" },
  { code: "FOIANO", name: "Magazzino Foiano" },
  { code: "MARLIA", name: "Magazzino Marlia" },
  { code: "SANMARCO", name: "Magazzino San Marco" },
  { code: "SANCONCORDIO", name: "Magazzino San Concordio" },
] as const;

export const ENTITIES = [
  { code: 0, name: "BMS", site: "BMS-SFILIPPO" },
  { code: 1, name: "FOIANO", site: "FOIANO" },
  { code: 2, name: "MARLIA", site: "MARLIA" },
  { code: 3, name: "SAN FILIPPO", site: "BMS-SFILIPPO" },
  { code: 4, name: "SAN MARCO", site: "SANMARCO" },
  { code: 5, name: "SAN CONCORDIO", site: "SANCONCORDIO" },
] as const;

/** Monday to Friday, 09:00–18:00, every site. */
export const WEEKDAY_HOURS = { opens: "09:00", closes: "18:00", weekdays: [1, 2, 3, 4, 5] };

/** Idempotent: safe to run on every deploy. Existing rows are left untouched. */
export async function seed(db: Db, opts: { holidayYears?: number[] } = {}) {
  const thisYear = new Date().getUTCFullYear();
  const years = opts.holidayYears ?? [thisYear, thisYear + 1];

  await db.transaction(async (tx) => {
    await tx.insert(site).values(SITES.map((s) => ({ ...s }))).onConflictDoNothing({ target: site.code });
    const sites = await tx.select({ id: site.id, code: site.code }).from(site);
    const siteId = (code: string) => sites.find((s) => s.code === code)!.id;

    await tx
      .insert(entity)
      .values(ENTITIES.map((e) => ({ code: e.code, name: e.name, siteId: siteId(e.site) })))
      .onConflictDoNothing({ target: entity.code });

    for (const s of SITES) {
      const id = siteId(s.code);
      // One bay per site for now.
      await tx.execute(sql`insert into dock (site_id, name) values (${id}, 'Baia 1') on conflict do nothing`);
      await tx.insert(bookingPolicy).values({ siteId: id }).onConflictDoNothing();
      for (const weekday of WEEKDAY_HOURS.weekdays) {
        await tx.execute(
          sql`insert into opening_hours (site_id, weekday, opens, closes)
              values (${id}, ${weekday}, ${WEEKDAY_HOURS.opens}, ${WEEKDAY_HOURS.closes})
              on conflict do nothing`,
        );
      }
    }

    for (const r of DEFAULT_SLOT_RULES) {
      await tx.insert(slotRule).values({ siteId: null, ...r }).onConflictDoNothing();
    }

    const holidays = years.flatMap((y) => italianHolidays(y));
    if (holidays.length) {
      await tx
        .insert(closure)
        .values(holidays.map((h) => ({ siteId: null, date: h.date, reason: h.name })))
        .onConflictDoNothing();
    }
  });
}
