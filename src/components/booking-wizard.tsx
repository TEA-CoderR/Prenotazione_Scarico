"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { VehicleType } from "@/db/schema";
import { formatDay } from "@/lib/i18n/messages";
import { DEFAULT_SLOT_RULES, LoadNotAllowedError, resolveDuration, type SlotRule } from "@/lib/scheduling/duration";
import { useLang } from "./lang";
import { Turnstile } from "./turnstile";
import { Button, buttonClass, ErrorNote, Field, inputClass, StepHeader } from "./ui";

interface EntityOption {
  code: number;
  name: string;
  siteName: string;
  sharedWith: string[];
}
interface Day {
  date: string;
  open: boolean;
}
interface Slot {
  time: string;
  start: string;
}
interface Created {
  code: string;
  time: string;
  manageUrl: string;
}

const VEHICLES: VehicleType[] = ["parcel", "van", "truck", "semi"];

type Details = {
  carrierName: string;
  contactEmail: string;
  driverName: string;
  driverPhone: string;
  plate: string;
  supplier: string;
  orderNo: string;
  ddtNo: string;
  notes: string;
};
const EMPTY_DETAILS: Details = {
  carrierName: "",
  contactEmail: "",
  driverName: "",
  driverPhone: "",
  plate: "",
  supplier: "",
  orderNo: "",
  ddtNo: "",
  notes: "",
};

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) throw new Error(String(res.status));
  return res.json() as Promise<T>;
}

export function BookingWizard() {
  const { t, lang } = useLang();
  const [step, setStep] = useState(0);

  const [entities, setEntities] = useState<EntityOption[] | null>(null);
  const [entitiesError, setEntitiesError] = useState(false);
  const [entity, setEntity] = useState<EntityOption | null>(null);

  const [vehicle, setVehicle] = useState<VehicleType | null>(null);
  const [qty, setQty] = useState("");

  const [days, setDays] = useState<Day[] | null>(null);
  const [date, setDate] = useState<string | null>(null);
  const [slots, setSlots] = useState<Slot[] | null>(null);
  const [duration, setDuration] = useState<number | null>(null);
  const [time, setTime] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [details, setDetails] = useState<Details>(EMPTY_DETAILS);
  const [consent, setConsent] = useState(false);
  const [showErrors, setShowErrors] = useState(false);

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [created, setCreated] = useState<Created | null>(null);

  // Captcha (Cloudflare Turnstile). siteKey "" = disabled on this server.
  const [siteKey, setSiteKey] = useState<string | null>(null);
  const [captchaToken, setCaptchaToken] = useState<string | null>(null);
  const [captchaReset, setCaptchaReset] = useState(0);
  const [captchaLoadError, setCaptchaLoadError] = useState(false);
  // Load limits and durations as configured by the admin (defaults until loaded).
  const [rules, setRules] = useState<SlotRule[]>(DEFAULT_SLOT_RULES);

  const fetchEntities = useCallback(
    () =>
      getJson<{ entities: EntityOption[] }>("/api/entities")
        .then((r) => setEntities(r.entities))
        .catch(() => setEntitiesError(true)),
    [],
  );
  useEffect(() => {
    fetchEntities();
    getJson<{ turnstileSiteKey: string | null; slotRules?: SlotRule[] }>("/api/config")
      .then((c) => {
        setSiteKey(c.turnstileSiteKey ?? "");
        if (c.slotRules?.length) setRules(c.slotRules);
      })
      .catch(() => setSiteKey(""));
  }, [fetchEntities]);
  const loadEntities = () => {
    setEntitiesError(false);
    fetchEntities();
  };

  const isParcel = vehicle === "parcel";
  const amount = Number(qty);
  const pallets = isParcel ? 0 : amount;
  const parcels = isParcel ? amount : 0;
  const rule = rules.find((r) => r.vehicleType === vehicle);
  const max = rule ? (isParcel ? rule.maxParcels ?? 0 : rule.maxPallets) : 0;
  let loadError: string | null = null;
  if (vehicle && qty !== "") {
    try {
      resolveDuration(rules, vehicle, pallets, parcels);
    } catch (e) {
      if (e instanceof LoadNotAllowedError) {
        loadError = amount < 1 ? t.atLeastOne : t.tooMany(max, isParcel ? t.unitParcels : t.unitPallets);
      }
    }
  }
  const loadValid = !!vehicle && qty !== "" && Number.isInteger(amount) && !loadError;

  // Step 3: load days for the chosen entity, then the times for the chosen day.
  useEffect(() => {
    if (step !== 2 || !entity) return;
    let live = true;
    getJson<{ days: Day[] }>(`/api/booking-days?entity=${entity.code}`)
      .then((r) => live && setDays(r.days))
      .catch(() => live && setDays([]));
    return () => {
      live = false;
    };
  }, [step, entity]);

  const loadSlots = useCallback(
    (d: string) => {
      if (!entity || !vehicle) return;
      setSlots(null);
      const q = new URLSearchParams({
        entity: String(entity.code),
        date: d,
        vehicle,
        pallets: String(pallets),
        parcels: String(parcels),
      });
      getJson<{ slots: Slot[]; durationMin: number }>(`/api/availability?${q}`)
        .then((r) => {
          setSlots(r.slots);
          setDuration(r.durationMin);
        })
        .catch(() => setSlots([]));
    },
    [entity, vehicle, pallets, parcels],
  );

  const chooseDate = (d: string) => {
    setDate(d);
    setTime(null);
    setNotice(null);
    loadSlots(d);
  };

  const trimmed = (k: keyof Details) => details[k].trim();
  const emailOk = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed("contactEmail"));
  const detailsValid = !!trimmed("carrierName") && emailOk && consent;

  async function submit() {
    if (!entity || !vehicle || !date || !time) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const res = await fetch("/api/bookings", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          entityCode: entity.code,
          date,
          time,
          vehicleType: vehicle,
          pallets,
          parcels,
          lang,
          captchaToken,
          ...Object.fromEntries(Object.entries(details).map(([k, v]) => [k, v.trim() || undefined])),
        }),
      });
      const body = await res.json().catch(() => ({}));
      if (res.ok) {
        setCreated(body as Created);
        window.scrollTo({ top: 0 });
        return;
      }
      setCaptchaReset((n) => n + 1); // the token was used up; get a new one
      const code: string = body.error ?? "GENERIC";
      if (code === "SLOT_UNAVAILABLE" || code === "DATE_NOT_BOOKABLE" || code === "SITE_CLOSED") {
        // Send the carrier back to pick another time, with fresh availability.
        setNotice(t.errors[code]);
        setTime(null);
        setStep(2);
        if (code === "SLOT_UNAVAILABLE") loadSlots(date);
        else setDate(null);
        return;
      }
      setSubmitError(t.errors[code] ?? t.errors.GENERIC);
    } catch {
      setCaptchaReset((n) => n + 1);
      setSubmitError(t.errors.GENERIC);
    } finally {
      setSubmitting(false);
    }
  }

  function restart() {
    setStep(0);
    setEntity(null);
    setVehicle(null);
    setQty("");
    setDays(null);
    setDate(null);
    setSlots(null);
    setTime(null);
    setNotice(null);
    setDetails((d) => ({ ...EMPTY_DETAILS, carrierName: d.carrierName, contactEmail: d.contactEmail }));
    setConsent(false);
    setShowErrors(false);
    setCreated(null);
  }

  if (created) {
    return (
      <section className="flex flex-col gap-6" aria-live="polite">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300">
            <svg viewBox="0 0 24 24" className="h-6 w-6" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M5 12l5 5 9-10" />
            </svg>
          </span>
          <h2 className="text-2xl font-semibold">{t.doneTitle}</h2>
        </div>
        <p className="text-slate-600 dark:text-slate-300">{t.doneLead}</p>
        <div className="rounded-2xl border-2 border-dashed border-slate-300 p-5 text-center dark:border-slate-700">
          <div className="text-sm text-slate-500">{t.doneCode}</div>
          <div className="mt-1 font-mono text-3xl font-semibold tracking-wider" data-testid="booking-code">
            {created.code}
          </div>
          <div className="mt-2 text-sm text-slate-500">{t.doneShow}</div>
        </div>
        <Summary
          rows={[
            [t.reviewTo, `${entity!.name} · ${entity!.siteName}`],
            [t.reviewWhen, `${formatDay(date!, lang, { weekday: "long", day: "numeric", month: "long" })}, ${created.time}`],
          ]}
        />
        <div className="flex flex-col gap-3 sm:flex-row">
          <Link href={new URL(created.manageUrl).pathname} className={buttonClass("secondary", "flex-1")}>
            {t.doneManage}
          </Link>
          <Button className="flex-1" onClick={restart}>
            {t.doneNew}
          </Button>
        </div>
      </section>
    );
  }

  const nav = (canNext: boolean, onNext?: () => void, nextLabel?: ReactNode) => (
    <div className="sticky bottom-0 -mx-4 mt-2 flex gap-3 border-t border-slate-200 bg-[var(--background)] px-4 py-3 dark:border-slate-800">
      {step > 0 && (
        <Button variant="secondary" onClick={() => setStep(step - 1)} disabled={submitting}>
          {t.back}
        </Button>
      )}
      <Button
        className="flex-1"
        disabled={!canNext || submitting}
        onClick={onNext ?? (() => setStep(step + 1))}
      >
        {nextLabel ?? t.next}
      </Button>
    </div>
  );

  return (
    <section className="flex flex-col gap-5">
      <StepHeader steps={t.steps} current={step} />

      {step === 0 && (
        <>
          <div>
            <h2 className="text-xl font-semibold">{t.entityTitle}</h2>
            <p className="mt-1 text-sm text-slate-500">{t.entityHint}</p>
          </div>
          {entitiesError && (
            <ErrorNote>
              {t.loadError}{" "}
              <button className="underline" onClick={loadEntities}>
                {t.retry}
              </button>
            </ErrorNote>
          )}
          {!entities && !entitiesError && <p className="text-slate-500">{t.loading}</p>}
          <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label={t.entityTitle}>
            {entities?.map((e) => (
              <Choice
                key={e.code}
                selected={entity?.code === e.code}
                onSelect={() => {
                  if (entity?.code !== e.code) {
                    setDate(null);
                    setTime(null);
                    setSlots(null);
                    setDays(null);
                  }
                  setEntity(e);
                }}
                title={e.name}
                badge={String(e.code)}
                subtitle={e.sharedWith.length ? t.sharedSite(e.sharedWith.join(", ")) : e.siteName}
              />
            ))}
          </div>
          {nav(!!entity)}
        </>
      )}

      {step === 1 && (
        <>
          <h2 className="text-xl font-semibold">{t.loadTitle}</h2>
          <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label={t.loadTitle}>
            {VEHICLES.map((v) => {
              const r = rules.find((x) => x.vehicleType === v)!;
              return (
                <Choice
                  key={v}
                  selected={vehicle === v}
                  onSelect={() => {
                    if (vehicle !== v) {
                      setQty("");
                      setTime(null);
                      setSlots(null);
                    }
                    setVehicle(v);
                  }}
                  title={t.vehicles[v].name}
                  subtitle={v === "parcel" ? t.upTo(r.maxParcels ?? 0, t.unitParcels) : t.upTo(r.maxPallets, t.unitPallets)}
                  badge={t.minutes(r.durationMin)}
                />
              );
            })}
          </div>
          {vehicle && (
            <Field label={isParcel ? t.parcels : t.pallets} error={loadError}>
              {(id) => (
                <input
                  id={id}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={max}
                  value={qty}
                  onChange={(e) => {
                    setQty(e.target.value);
                    setTime(null);
                    setSlots(null);
                  }}
                  className={`${inputClass} max-w-40 text-lg`}
                  autoFocus
                />
              )}
            </Field>
          )}
          {nav(loadValid, () => {
            setStep(2);
            if (date) loadSlots(date);
          })}
        </>
      )}

      {step === 2 && (
        <>
          <div>
            <h2 className="text-xl font-semibold">{t.dateTitle}</h2>
            <p className="mt-1 text-sm text-slate-500">{t.dateHint}</p>
          </div>
          {notice && <ErrorNote>{notice}</ErrorNote>}
          {!days && <p className="text-slate-500">{t.loading}</p>}
          <div className="grid grid-cols-4 gap-2 sm:grid-cols-5" role="radiogroup" aria-label={t.dateTitle}>
            {days?.map((d) => {
              const selected = date === d.date;
              return (
                <button
                  key={d.date}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  disabled={!d.open}
                  onClick={() => chooseDate(d.date)}
                  className={`flex flex-col items-center rounded-xl border px-1 py-2 text-sm transition-colors ${
                    selected
                      ? "border-blue-700 bg-blue-700 text-white"
                      : d.open
                        ? "border-slate-300 hover:border-blue-700 dark:border-slate-700"
                        : "cursor-not-allowed border-transparent text-slate-400 line-through dark:text-slate-600"
                  }`}
                >
                  <span className="text-xs uppercase">{formatDay(d.date, lang, { weekday: "short" })}</span>
                  <span className="text-lg font-semibold leading-tight">{formatDay(d.date, lang, { day: "numeric" })}</span>
                  <span className="text-xs">{d.open ? formatDay(d.date, lang, { month: "short" }) : t.closed}</span>
                </button>
              );
            })}
          </div>

          {date && (
            <div className="flex flex-col gap-3">
              <div>
                <h3 className="font-semibold">
                  {t.timeTitle} · {formatDay(date, lang, { weekday: "long", day: "numeric", month: "long" })}
                </h3>
                {duration && <p className="text-sm text-slate-500">{t.occupies(duration)}</p>}
              </div>
              {!slots && <p className="text-slate-500">{t.loading}</p>}
              {slots && slots.length === 0 && <p className="text-slate-600 dark:text-slate-300">{t.noSlots}</p>}
              {slots && slots.length > 0 && (
                <div className="flex flex-col gap-3">
                  {[
                    [t.morning, slots.filter((s) => s.time < "13:00")],
                    [t.afternoon, slots.filter((s) => s.time >= "13:00")],
                  ].map(([label, list]) =>
                    (list as Slot[]).length ? (
                      <div key={label as string}>
                        <div className="mb-1 text-xs font-medium uppercase tracking-wide text-slate-500">{label as string}</div>
                        <div className="grid grid-cols-4 gap-2 sm:grid-cols-6" role="radiogroup" aria-label={label as string}>
                          {(list as Slot[]).map((s) => (
                            <button
                              key={s.start}
                              type="button"
                              role="radio"
                              aria-checked={time === s.time}
                              onClick={() => {
                                setTime(s.time);
                                setNotice(null);
                              }}
                              className={`rounded-lg border py-2 font-medium tabular-nums transition-colors ${
                                time === s.time
                                  ? "border-blue-700 bg-blue-700 text-white"
                                  : "border-slate-300 hover:border-blue-700 dark:border-slate-700"
                              }`}
                            >
                              {s.time}
                            </button>
                          ))}
                        </div>
                      </div>
                    ) : null,
                  )}
                </div>
              )}
            </div>
          )}
          {nav(!!date && !!time)}
        </>
      )}

      {step === 3 && (
        <>
          <h2 className="text-xl font-semibold">{t.detailsTitle}</h2>
          <div className="flex flex-col gap-4">
            <TextField k="carrierName" label={t.carrierName} required autoComplete="organization" details={details} setDetails={setDetails} error={showErrors && !trimmed("carrierName") ? t.required : null} />
            <TextField
              k="contactEmail"
              label={t.contactEmail}
              required
              type="email"
              autoComplete="email"
              details={details}
              setDetails={setDetails}
              error={showErrors && !emailOk ? (trimmed("contactEmail") ? t.invalidEmail : t.required) : null}
            />
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField k="driverName" label={t.driverName} optional={t.optional} autoComplete="name" details={details} setDetails={setDetails} />
              <TextField k="driverPhone" label={t.driverPhone} optional={t.optional} type="tel" autoComplete="tel" details={details} setDetails={setDetails} />
              <TextField k="plate" label={t.plate} optional={t.optional} details={details} setDetails={setDetails} upper />
              <TextField k="supplier" label={t.supplier} optional={t.optional} details={details} setDetails={setDetails} />
              <TextField k="orderNo" label={t.orderNo} optional={t.optional} details={details} setDetails={setDetails} />
              <TextField k="ddtNo" label={t.ddtNo} optional={t.optional} details={details} setDetails={setDetails} />
            </div>
            <Field label={t.notes} hint={t.optional}>
              {(id) => (
                <textarea
                  id={id}
                  rows={3}
                  maxLength={500}
                  value={details.notes}
                  onChange={(e) => setDetails({ ...details, notes: e.target.value })}
                  className={inputClass}
                />
              )}
            </Field>
            <div>
              <label className="flex items-start gap-3">
                <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} className="mt-1 h-5 w-5 accent-blue-700" />
                <span>
                  {t.privacy} ·{" "}
                  <Link href="/privacy" target="_blank" className="text-blue-700 underline dark:text-blue-400">
                    {t.privacyLink}
                  </Link>
                </span>
              </label>
              {showErrors && !consent && <p className="mt-1 text-sm text-red-700 dark:text-red-400">{t.privacyRequired}</p>}
            </div>
          </div>
          {nav(true, () => {
            if (detailsValid) setStep(4);
            else setShowErrors(true);
          })}
        </>
      )}

      {step === 4 && entity && vehicle && date && time && (
        <>
          <h2 className="text-xl font-semibold">{t.reviewTitle}</h2>
          <Summary
            rows={[
              [t.reviewTo, `${entity.name} · ${entity.siteName}`],
              [t.reviewWhen, `${formatDay(date, lang, { weekday: "long", day: "numeric", month: "long" })}, ${time}`],
              [t.reviewLoad, `${t.vehicles[vehicle].name} · ${amount} ${isParcel ? t.unitParcels : t.unitPallets}`],
              [t.reviewCarrier, [trimmed("carrierName"), trimmed("contactEmail")].join(" · ")],
              ...(["driverName", "plate", "orderNo", "ddtNo"] as const)
                .filter((k) => trimmed(k))
                .map((k) => [t[k], trimmed(k)] as [string, string]),
            ]}
          />
          {siteKey && (
            <div className="flex flex-col gap-1">
              <Turnstile
                siteKey={siteKey}
                lang={lang}
                resetKey={captchaReset}
                onToken={setCaptchaToken}
                onLoadError={() => setCaptchaLoadError(true)}
              />
              {captchaLoadError ? (
                <ErrorNote>{t.errors.CAPTCHA_LOAD}</ErrorNote>
              ) : (
                !captchaToken && <p className="text-sm text-slate-500">{t.captchaHint}</p>
              )}
            </div>
          )}
          {submitError && <ErrorNote>{submitError}</ErrorNote>}
          {nav(siteKey === "" || !!captchaToken, submit, submitting ? t.submitting : t.submit)}
        </>
      )}
    </section>
  );
}

function Choice(props: { selected: boolean; onSelect: () => void; title: string; subtitle: string; badge?: string }) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={props.selected}
      onClick={props.onSelect}
      className={`flex items-center justify-between gap-3 rounded-xl border-2 px-4 py-3 text-left transition-colors ${
        props.selected
          ? "border-blue-700 bg-blue-50 dark:bg-blue-950/40"
          : "border-slate-200 hover:border-slate-400 dark:border-slate-800 dark:hover:border-slate-600"
      }`}
    >
      <span>
        <span className="block font-semibold">{props.title}</span>
        <span className="block text-sm text-slate-500">{props.subtitle}</span>
      </span>
      {props.badge && (
        <span className="shrink-0 rounded-md bg-slate-100 px-2 py-0.5 text-sm tabular-nums text-slate-600 dark:bg-slate-800 dark:text-slate-300">
          {props.badge}
        </span>
      )}
    </button>
  );
}

function TextField(props: {
  k: keyof Details;
  label: string;
  details: Details;
  setDetails: (d: Details) => void;
  required?: boolean;
  optional?: string;
  type?: string;
  autoComplete?: string;
  upper?: boolean;
  error?: string | null;
}) {
  return (
    <Field label={props.label} hint={props.optional} error={props.error}>
      {(id) => (
        <input
          id={id}
          type={props.type ?? "text"}
          autoComplete={props.autoComplete ?? "off"}
          required={props.required}
          aria-invalid={!!props.error}
          value={props.details[props.k]}
          maxLength={props.k === "carrierName" || props.k === "driverName" || props.k === "supplier" ? 120 : 60}
          onChange={(e) =>
            props.setDetails({ ...props.details, [props.k]: props.upper ? e.target.value.toUpperCase() : e.target.value })
          }
          className={inputClass}
        />
      )}
    </Field>
  );
}

function Summary({ rows }: { rows: [string, string][] }) {
  return (
    <dl className="divide-y divide-slate-200 rounded-xl border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
      {rows.map(([k, v]) => (
        <div key={k} className="flex flex-col gap-0.5 px-4 py-3 sm:flex-row sm:gap-4">
          <dt className="shrink-0 text-sm text-slate-500 sm:w-36">{k}</dt>
          <dd className="font-medium break-words">{v}</dd>
        </div>
      ))}
    </dl>
  );
}
