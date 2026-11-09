// Booking e-mails. They intentionally contain no personal data (driver name, phone, plate):
// only the booking code, the slot, the site and the manage link.

export type Lang = "it" | "en";
export type MailKind = "requested" | "confirmed" | "rejected" | "cancelled";

export interface BookingMailData {
  code: string;
  entityName: string;
  siteName: string;
  siteAddress: string | null;
  date: string; // YYYY-MM-DD, local
  time: string; // HH:MM, local
  manageUrl: string;
}

const subjects: Record<Lang, Record<MailKind, string>> = {
  it: {
    requested: "Richiesta di scarico ricevuta",
    confirmed: "Scarico confermato",
    rejected: "Richiesta di scarico non accettata",
    cancelled: "Prenotazione di scarico annullata",
  },
  en: {
    requested: "Unloading request received",
    confirmed: "Unloading confirmed",
    rejected: "Unloading request declined",
    cancelled: "Unloading booking cancelled",
  },
};

const leads: Record<Lang, Record<MailKind, string>> = {
  it: {
    requested: "Abbiamo ricevuto la sua richiesta. Il magazzino la confermerà a breve; il posto è già riservato.",
    confirmed: "La sua prenotazione è confermata. Si presenti all'orario indicato.",
    rejected: "Il magazzino non ha potuto accettare la richiesta. Può prenotare un altro orario.",
    cancelled: "La prenotazione è stata annullata.",
  },
  en: {
    requested: "We received your request. The warehouse will confirm it shortly; the slot is already held for you.",
    confirmed: "Your booking is confirmed. Please arrive at the time shown.",
    rejected: "The warehouse could not accept this request. You can book another time.",
    cancelled: "The booking has been cancelled.",
  },
};

const labels: Record<Lang, { code: string; when: string; where: string; manage: string }> = {
  it: { code: "Codice", when: "Data e ora", where: "Luogo", manage: "Gestisci o annulla la prenotazione" },
  en: { code: "Code", when: "Date and time", where: "Location", manage: "Manage or cancel the booking" },
};

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

export function renderBookingMail(kind: MailKind, lang: Lang, d: BookingMailData) {
  const l = labels[lang];
  const subject = `${subjects[lang][kind]} – ${d.code}`;
  const where = [d.entityName, d.siteName, d.siteAddress].filter(Boolean).join(", ");
  const rows: [string, string][] = [
    [l.code, d.code],
    [l.when, `${d.date} ${d.time}`],
    [l.where, where],
  ];
  const text = [leads[lang][kind], "", ...rows.map(([k, v]) => `${k}: ${v}`), "", `${l.manage}: ${d.manageUrl}`].join("\n");
  const html = `<p>${escapeHtml(leads[lang][kind])}</p><table>${rows
    .map(([k, v]) => `<tr><td><b>${escapeHtml(k)}</b></td><td>${escapeHtml(v)}</td></tr>`)
    .join("")}</table><p><a href="${escapeHtml(d.manageUrl)}">${escapeHtml(l.manage)}</a></p>`;
  return { subject, text, html };
}
