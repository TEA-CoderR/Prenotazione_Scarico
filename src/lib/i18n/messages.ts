import type { BookingStatus, VehicleType } from "@/db/schema";

export type Lang = "it" | "en";
export const LANGS: Lang[] = ["it", "en"];

const it = {
  appTitle: "Prenotazione scarico merci",
  steps: ["Destinatario", "Carico", "Data e ora", "Dati", "Riepilogo"],
  back: "Indietro",
  next: "Avanti",
  loading: "Caricamento…",
  loadError: "Impossibile caricare i dati. Riprovi tra poco.",
  retry: "Riprova",

  entityTitle: "A chi consegna?",
  entityHint: "Scelga il destinatario indicato sul documento di trasporto.",
  sharedSite: (others: string) => `Stesso magazzino di ${others}`,

  loadTitle: "Che cosa scarica?",
  vehicles: {
    parcel: { name: "Corriere / colli", limit: "fino a 20 colli" },
    van: { name: "Furgone", limit: "fino a 6 pallet" },
    truck: { name: "Motrice", limit: "fino a 18 pallet" },
    semi: { name: "Bilico", limit: "fino a 33 pallet" },
  } satisfies Record<VehicleType, { name: string; limit: string }>,
  minutes: (n: number) => `${n} min`,
  pallets: "Numero di pallet",
  parcels: "Numero di colli",
  tooMany: (max: number, unit: string) => `Massimo ${max} ${unit} per questo mezzo. Scelga un mezzo più grande.`,
  atLeastOne: "Indichi almeno 1.",
  unitPallets: "pallet",
  unitParcels: "colli",

  dateTitle: "Quando arriva?",
  dateHint: "Le prenotazioni per domani si chiudono alle 16:00 di oggi.",
  closed: "chiuso",
  timeTitle: "Orario di arrivo",
  occupies: (n: number) => `Lo scarico occupa la baia per ${n} minuti.`,
  noSlots: "Nessun orario libero in questo giorno. Scelga un altro giorno.",
  morning: "Mattina",
  afternoon: "Pomeriggio",

  detailsTitle: "I suoi dati",
  carrierName: "Azienda di trasporto",
  contactEmail: "E-mail per la conferma",
  driverName: "Nome autista",
  driverPhone: "Telefono autista",
  plate: "Targa",
  supplier: "Fornitore / mittente",
  orderNo: "Numero ordine",
  ddtNo: "Numero DDT",
  notes: "Note per il magazzino",
  optional: "facoltativo",
  required: "Campo obbligatorio",
  invalidEmail: "Indirizzo e-mail non valido",
  privacy: "Ho letto l'informativa privacy",
  privacyLink: "Informativa privacy",
  privacyRequired: "Serve il consenso per inviare la richiesta",

  reviewTitle: "Controlli e invii",
  reviewTo: "Destinatario",
  reviewWhen: "Data e ora",
  reviewLoad: "Carico",
  reviewCarrier: "Trasportatore",
  submit: "Invia richiesta",
  submitting: "Invio in corso…",

  doneTitle: "Richiesta inviata",
  doneLead: "Il posto è riservato. Le abbiamo inviato un'e-mail di riepilogo; ne riceverà un'altra quando il magazzino confermerà.",
  doneCode: "Codice prenotazione",
  doneShow: "Mostri questo codice all'arrivo.",
  doneManage: "Gestisci o annulla",
  doneNew: "Nuova prenotazione",

  errors: {
    SLOT_UNAVAILABLE: "Questo orario è appena stato preso. Scelga un altro orario.",
    DATE_NOT_BOOKABLE: "Questo giorno non è più prenotabile. Scelga un altro giorno.",
    SITE_CLOSED: "Il magazzino è chiuso in questo giorno.",
    LOAD_NOT_ALLOWED: "Il carico non è compatibile con il mezzo scelto.",
    DAILY_CAP_REACHED: "Il magazzino ha raggiunto la capacità per questo giorno.",
    RATE_LIMITED: "Troppe richieste. Riprovi tra qualche minuto.",
    TOO_LATE: "Troppo tardi per annullare online. Contatti il magazzino.",
    BOOKING_NOT_FOUND: "Prenotazione non trovata. Controlli il link ricevuto per e-mail.",
    GENERIC: "Qualcosa non ha funzionato. Riprovi.",
  } as Record<string, string>,

  manageTitle: "La sua prenotazione",
  status: {
    requested: "In attesa di conferma",
    confirmed: "Confermata",
    rejected: "Non accettata",
    cancelled: "Annullata",
    arrived: "Arrivato",
    unloading: "In scarico",
    completed: "Completata",
    no_show: "Non presentato",
  } satisfies Record<BookingStatus, string>,
  cancel: "Annulla prenotazione",
  cancelConfirm: "Confermi l'annullamento",
  cancelKeep: "No, mantieni",
  cancelUntil: (when: string) => `Annullabile online fino a ${when}.`,
  cancelClosed: "Non è più possibile annullare online. Per modifiche contatti il magazzino.",
  cancelled: "Prenotazione annullata. L'orario è di nuovo disponibile.",
  changeHint: "Per cambiare orario annulli e prenoti di nuovo.",
};

export type Messages = typeof it;

const en: Messages = {
  appTitle: "Unloading booking",
  steps: ["Recipient", "Load", "Date & time", "Details", "Review"],
  back: "Back",
  next: "Next",
  loading: "Loading…",
  loadError: "Could not load data. Please try again shortly.",
  retry: "Retry",

  entityTitle: "Who are you delivering to?",
  entityHint: "Choose the recipient shown on the delivery note.",
  sharedSite: (others: string) => `Same warehouse as ${others}`,

  loadTitle: "What are you unloading?",
  vehicles: {
    parcel: { name: "Courier / parcels", limit: "up to 20 parcels" },
    van: { name: "Van", limit: "up to 6 pallets" },
    truck: { name: "Rigid truck", limit: "up to 18 pallets" },
    semi: { name: "Articulated lorry", limit: "up to 33 pallets" },
  },
  minutes: (n: number) => `${n} min`,
  pallets: "Number of pallets",
  parcels: "Number of parcels",
  tooMany: (max: number, unit: string) => `At most ${max} ${unit} for this vehicle. Choose a larger one.`,
  atLeastOne: "Enter at least 1.",
  unitPallets: "pallets",
  unitParcels: "parcels",

  dateTitle: "When will you arrive?",
  dateHint: "Bookings for tomorrow close at 16:00 today.",
  closed: "closed",
  timeTitle: "Arrival time",
  occupies: (n: number) => `Unloading occupies the bay for ${n} minutes.`,
  noSlots: "No free times on this day. Please choose another day.",
  morning: "Morning",
  afternoon: "Afternoon",

  detailsTitle: "Your details",
  carrierName: "Transport company",
  contactEmail: "E-mail for the confirmation",
  driverName: "Driver name",
  driverPhone: "Driver phone",
  plate: "Number plate",
  supplier: "Supplier / sender",
  orderNo: "Order number",
  ddtNo: "Delivery note (DDT) number",
  notes: "Notes for the warehouse",
  optional: "optional",
  required: "Required",
  invalidEmail: "Invalid e-mail address",
  privacy: "I have read the privacy notice",
  privacyLink: "Privacy notice",
  privacyRequired: "Consent is needed to send the request",

  reviewTitle: "Check and send",
  reviewTo: "Recipient",
  reviewWhen: "Date and time",
  reviewLoad: "Load",
  reviewCarrier: "Carrier",
  submit: "Send request",
  submitting: "Sending…",

  doneTitle: "Request sent",
  doneLead: "Your slot is held. We have sent you a summary e-mail; you will get another one when the warehouse confirms.",
  doneCode: "Booking code",
  doneShow: "Show this code when you arrive.",
  doneManage: "Manage or cancel",
  doneNew: "New booking",

  errors: {
    SLOT_UNAVAILABLE: "This time was just taken. Please choose another time.",
    DATE_NOT_BOOKABLE: "This day can no longer be booked. Please choose another day.",
    SITE_CLOSED: "The warehouse is closed on this day.",
    LOAD_NOT_ALLOWED: "The load does not fit the chosen vehicle.",
    DAILY_CAP_REACHED: "The warehouse is full for this day.",
    RATE_LIMITED: "Too many requests. Please try again in a few minutes.",
    TOO_LATE: "Too late to cancel online. Please contact the warehouse.",
    BOOKING_NOT_FOUND: "Booking not found. Please check the link in your e-mail.",
    GENERIC: "Something went wrong. Please try again.",
  },

  manageTitle: "Your booking",
  status: {
    requested: "Awaiting confirmation",
    confirmed: "Confirmed",
    rejected: "Declined",
    cancelled: "Cancelled",
    arrived: "Arrived",
    unloading: "Unloading",
    completed: "Completed",
    no_show: "No-show",
  },
  cancel: "Cancel booking",
  cancelConfirm: "Confirm cancellation",
  cancelKeep: "No, keep it",
  cancelUntil: (when: string) => `Can be cancelled online until ${when}.`,
  cancelClosed: "It can no longer be cancelled online. Please contact the warehouse for changes.",
  cancelled: "Booking cancelled. The time is available again.",
  changeHint: "To change the time, cancel and book again.",
};

export const messages: Record<Lang, Messages> = { it, en };

const locale: Record<Lang, string> = { it: "it-IT", en: "en-GB" };

/** "mar 10 nov" / "Tue 10 Nov" for a YYYY-MM-DD date. */
export function formatDay(ymd: string, lang: Lang, opts: Intl.DateTimeFormatOptions = { weekday: "short", day: "numeric", month: "short" }) {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Intl.DateTimeFormat(locale[lang], { ...opts, timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, d)));
}

export function formatInstant(iso: string, lang: Lang) {
  return new Intl.DateTimeFormat(locale[lang], {
    weekday: "short",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "Europe/Rome",
  }).format(new Date(iso));
}
