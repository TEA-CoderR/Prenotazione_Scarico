// Italian national public holidays (festività nazionali). Local feasts such as a town's
// patron saint are site-specific and are added as `closure` rows per site.

/** Easter Sunday (Gregorian), Anonymous Gregorian algorithm. Returns "YYYY-MM-DD". */
export function easterSunday(year: number): string {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

export interface Holiday {
  date: string;
  name: string;
}

export function italianHolidays(year: number): Holiday[] {
  const easter = easterSunday(year);
  const [ey, em, ed] = easter.split("-").map(Number);
  const easterMonday = new Date(Date.UTC(ey, em - 1, ed + 1)).toISOString().slice(0, 10);
  const fixed: [string, string][] = [
    ["01-01", "Capodanno"],
    ["01-06", "Epifania"],
    ["04-25", "Festa della Liberazione"],
    ["05-01", "Festa del Lavoro"],
    ["06-02", "Festa della Repubblica"],
    ["08-15", "Ferragosto"],
    ["11-01", "Ognissanti"],
    ["12-08", "Immacolata Concezione"],
    ["12-25", "Natale"],
    ["12-26", "Santo Stefano"],
  ];
  // San Francesco d'Assisi is a national holiday again from 2026.
  if (year >= 2026) fixed.push(["10-04", "San Francesco d'Assisi"]);

  const list: Holiday[] = fixed.map(([md, name]) => ({ date: `${year}-${md}`, name }));
  list.push({ date: easter, name: "Pasqua" });
  list.push({ date: easterMonday, name: "Lunedì dell'Angelo" });
  return list.sort((x, y) => x.date.localeCompare(y.date));
}
