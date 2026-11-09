// Typed mirror of drizzle/0000_init.sql. The SQL migrations are the source of truth
// (they carry the exclusion constraint Drizzle cannot express); keep this file in sync.
import {
  bigserial,
  bigint,
  boolean,
  date,
  integer,
  numeric,
  pgEnum,
  pgTable,
  primaryKey,
  serial,
  smallint,
  text,
  time,
  timestamp,
} from "drizzle-orm/pg-core";

export const bookingStatus = pgEnum("booking_status", [
  "requested",
  "confirmed",
  "rejected",
  "cancelled",
  "arrived",
  "unloading",
  "completed",
  "no_show",
]);
export const vehicleType = pgEnum("vehicle_type", ["parcel", "van", "truck", "semi"]);
export const userRole = pgEnum("user_role", ["warehouse_clerk", "warehouse_manager", "admin"]);
export const carrierStatus = pgEnum("carrier_status", ["active", "whitelisted", "blocked"]);
export const actorType = pgEnum("actor_type", ["carrier", "user", "system"]);

export type BookingStatus = (typeof bookingStatus.enumValues)[number];
export type VehicleType = (typeof vehicleType.enumValues)[number];
export type UserRole = (typeof userRole.enumValues)[number];
export type ActorType = (typeof actorType.enumValues)[number];

const tstz = (name: string) => timestamp(name, { withTimezone: true, mode: "date" });

export const site = pgTable("site", {
  id: serial("id").primaryKey(),
  code: text("code").notNull().unique(),
  name: text("name").notNull(),
  address: text("address"),
  latitude: numeric("latitude", { precision: 9, scale: 6 }),
  longitude: numeric("longitude", { precision: 9, scale: 6 }),
  timezone: text("timezone").notNull().default("Europe/Rome"),
  isActive: boolean("is_active").notNull().default(true),
  createdAt: tstz("created_at").notNull().defaultNow(),
});

export const entity = pgTable("entity", {
  id: serial("id").primaryKey(),
  code: smallint("code").notNull().unique(),
  name: text("name").notNull(),
  legalName: text("legal_name"),
  siteId: integer("site_id")
    .notNull()
    .references(() => site.id),
  isActive: boolean("is_active").notNull().default(true),
});

export const dock = pgTable("dock", {
  id: serial("id").primaryKey(),
  siteId: integer("site_id")
    .notNull()
    .references(() => site.id),
  name: text("name").notNull(),
  isActive: boolean("is_active").notNull().default(true),
});

export const openingHours = pgTable("opening_hours", {
  id: serial("id").primaryKey(),
  siteId: integer("site_id")
    .notNull()
    .references(() => site.id),
  weekday: smallint("weekday").notNull(),
  opens: time("opens").notNull(),
  closes: time("closes").notNull(),
});

export const closure = pgTable("closure", {
  id: serial("id").primaryKey(),
  siteId: integer("site_id").references(() => site.id),
  date: date("date", { mode: "string" }).notNull(),
  reason: text("reason").notNull(),
});

export const slotRule = pgTable("slot_rule", {
  id: serial("id").primaryKey(),
  siteId: integer("site_id").references(() => site.id),
  vehicleType: vehicleType("vehicle_type").notNull(),
  maxPallets: integer("max_pallets").notNull(),
  maxParcels: integer("max_parcels"),
  durationMin: integer("duration_min").notNull(),
});

export const bookingPolicy = pgTable("booking_policy", {
  siteId: integer("site_id")
    .primaryKey()
    .references(() => site.id),
  maxDaysAhead: integer("max_days_ahead").notNull().default(14),
  bookingCutoffTime: time("booking_cutoff_time").notNull().default("16:00"),
  cancelCutoffHours: integer("cancel_cutoff_hours").notNull().default(12),
  lateToleranceMin: integer("late_tolerance_min").notNull().default(15),
  noShowAfterMin: integer("no_show_after_min").notNull().default(30),
  confirmSlaHours: integer("confirm_sla_hours").notNull().default(4),
  dailyPalletCap: integer("daily_pallet_cap"),
});

export const carrier = pgTable("carrier", {
  id: serial("id").primaryKey(),
  name: text("name").notNull(),
  vatNumber: text("vat_number").unique(),
  email: text("email").notNull(),
  phone: text("phone"),
  status: carrierStatus("status").notNull().default("active"),
  createdAt: tstz("created_at").notNull().defaultNow(),
});

// `period` (generated tstzrange) is intentionally not mapped: the database computes it.
export const booking = pgTable("booking", {
  id: bigserial("id", { mode: "number" }).primaryKey(),
  code: text("code").notNull().unique(),
  entityId: integer("entity_id")
    .notNull()
    .references(() => entity.id),
  siteId: integer("site_id")
    .notNull()
    .references(() => site.id),
  dockId: integer("dock_id")
    .notNull()
    .references(() => dock.id),
  startAt: tstz("start_at").notNull(),
  endAt: tstz("end_at").notNull(),
  status: bookingStatus("status").notNull().default("requested"),
  carrierId: integer("carrier_id").references(() => carrier.id),
  carrierName: text("carrier_name").notNull(),
  contactEmail: text("contact_email").notNull(),
  driverName: text("driver_name"),
  driverPhone: text("driver_phone"),
  plate: text("plate"),
  vehicleType: vehicleType("vehicle_type").notNull(),
  pallets: integer("pallets").notNull().default(0),
  parcels: integer("parcels").notNull().default(0),
  supplier: text("supplier"),
  orderNo: text("order_no"),
  ddtNo: text("ddt_no"),
  notes: text("notes"),
  manageTokenHash: text("manage_token_hash").notNull(),
  lang: text("lang", { enum: ["it", "en"] }).notNull().default("it"),
  anonymizedAt: tstz("anonymized_at"),
  createdAt: tstz("created_at").notNull().defaultNow(),
  updatedAt: tstz("updated_at").notNull().defaultNow(),
});

export const appUser = pgTable("app_user", {
  id: serial("id").primaryKey(),
  email: text("email").notNull().unique(),
  name: text("name").notNull(),
  role: userRole("role").notNull(),
  passwordHash: text("password_hash"),
  totpSecret: text("totp_secret"),
  isActive: boolean("is_active").notNull().default(true),
  createdAt: tstz("created_at").notNull().defaultNow(),
});

export const userSite = pgTable(
  "user_site",
  {
    userId: integer("user_id")
      .notNull()
      .references(() => appUser.id),
    siteId: integer("site_id")
      .notNull()
      .references(() => site.id),
  },
  (t) => [primaryKey({ columns: [t.userId, t.siteId] })],
);

export const bookingEvent = pgTable("booking_event", {
  id: bigserial("id", { mode: "number" }).primaryKey(),
  bookingId: bigint("booking_id", { mode: "number" })
    .notNull()
    .references(() => booking.id),
  fromStatus: bookingStatus("from_status"),
  toStatus: bookingStatus("to_status").notNull(),
  actorType: actorType("actor_type").notNull(),
  actorUserId: integer("actor_user_id").references(() => appUser.id),
  note: text("note"),
  createdAt: tstz("created_at").notNull().defaultNow(),
});

export const incident = pgTable("incident", {
  id: bigserial("id", { mode: "number" }).primaryKey(),
  bookingId: bigint("booking_id", { mode: "number" })
    .notNull()
    .references(() => booking.id),
  type: text("type").notNull(),
  note: text("note"),
  createdBy: integer("created_by").references(() => appUser.id),
  createdAt: tstz("created_at").notNull().defaultNow(),
});

export const notificationLog = pgTable("notification_log", {
  id: bigserial("id", { mode: "number" }).primaryKey(),
  bookingId: bigint("booking_id", { mode: "number" }).references(() => booking.id),
  channel: text("channel").notNull(),
  template: text("template").notNull(),
  recipient: text("recipient").notNull(),
  ok: boolean("ok").notNull(),
  error: text("error"),
  sentAt: tstz("sent_at").notNull().defaultNow(),
});

export const staffSession = pgTable("staff_session", {
  tokenHash: text("token_hash").primaryKey(),
  userId: integer("user_id")
    .notNull()
    .references(() => appUser.id),
  createdAt: tstz("created_at").notNull().defaultNow(),
  expiresAt: tstz("expires_at").notNull(),
});
