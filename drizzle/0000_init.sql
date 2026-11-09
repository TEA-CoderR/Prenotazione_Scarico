-- Prenotazione Scarico: initial schema.
-- Capacity is managed per physical dock; business ownership per entity.
-- Entities 0 (BMS) and 3 (SAN FILIPPO) point at the same site, so they share its dock.

CREATE EXTENSION IF NOT EXISTS btree_gist;
--> statement-breakpoint
CREATE TYPE booking_status AS ENUM (
  'requested', 'confirmed', 'rejected', 'cancelled',
  'arrived', 'unloading', 'completed', 'no_show'
);
--> statement-breakpoint
CREATE TYPE vehicle_type AS ENUM ('parcel', 'van', 'truck', 'semi');
--> statement-breakpoint
CREATE TYPE user_role AS ENUM ('warehouse_clerk', 'warehouse_manager', 'admin');
--> statement-breakpoint
CREATE TYPE carrier_status AS ENUM ('active', 'whitelisted', 'blocked');
--> statement-breakpoint
CREATE TYPE actor_type AS ENUM ('carrier', 'user', 'system');
--> statement-breakpoint

-- Physical unloading location (sede di scarico).
CREATE TABLE site (
  id          serial PRIMARY KEY,
  code        text NOT NULL UNIQUE,
  name        text NOT NULL,
  address     text,
  latitude    numeric(9, 6),
  longitude   numeric(9, 6),
  timezone    text NOT NULL DEFAULT 'Europe/Rome',
  is_active   boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint

-- Business entity (entità) that receives the goods. code = 0..5 as used internally.
CREATE TABLE entity (
  id          serial PRIMARY KEY,
  code        smallint NOT NULL UNIQUE CHECK (code >= 0),
  name        text NOT NULL,
  legal_name  text,
  site_id     integer NOT NULL REFERENCES site (id),
  is_active   boolean NOT NULL DEFAULT true
);
--> statement-breakpoint

-- Unloading bay (baia). Capacity unit: one booking per dock at a time.
CREATE TABLE dock (
  id          serial PRIMARY KEY,
  site_id     integer NOT NULL REFERENCES site (id),
  name        text NOT NULL,
  is_active   boolean NOT NULL DEFAULT true,
  UNIQUE (site_id, name)
);
--> statement-breakpoint

-- Receiving hours. weekday = ISO (1 Monday .. 7 Sunday). A lunch break is two rows.
CREATE TABLE opening_hours (
  id          serial PRIMARY KEY,
  site_id     integer NOT NULL REFERENCES site (id) ON DELETE CASCADE,
  weekday     smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),
  opens       time NOT NULL,
  closes      time NOT NULL,
  CHECK (closes > opens),
  UNIQUE (site_id, weekday, opens)
);
--> statement-breakpoint

-- Closed days. site_id NULL = all sites (national holidays).
CREATE TABLE closure (
  id          serial PRIMARY KEY,
  site_id     integer REFERENCES site (id) ON DELETE CASCADE,
  date        date NOT NULL,
  reason      text NOT NULL,
  UNIQUE NULLS NOT DISTINCT (site_id, date)
);
--> statement-breakpoint

-- How long a dock is occupied, by vehicle type and load. site_id NULL = default rule.
CREATE TABLE slot_rule (
  id            serial PRIMARY KEY,
  site_id       integer REFERENCES site (id) ON DELETE CASCADE,
  vehicle_type  vehicle_type NOT NULL,
  max_pallets   integer NOT NULL CHECK (max_pallets >= 0),
  max_parcels   integer CHECK (max_parcels >= 0),
  duration_min  integer NOT NULL CHECK (duration_min > 0 AND duration_min % 15 = 0),
  UNIQUE NULLS NOT DISTINCT (site_id, vehicle_type)
);
--> statement-breakpoint

-- Booking rules per site.
CREATE TABLE booking_policy (
  site_id              integer PRIMARY KEY REFERENCES site (id) ON DELETE CASCADE,
  max_days_ahead       integer NOT NULL DEFAULT 14 CHECK (max_days_ahead > 0),
  -- A booking for day D must be placed before this local time on day D-1.
  booking_cutoff_time  time NOT NULL DEFAULT '16:00',
  cancel_cutoff_hours  integer NOT NULL DEFAULT 12 CHECK (cancel_cutoff_hours >= 0),
  late_tolerance_min   integer NOT NULL DEFAULT 15,
  no_show_after_min    integer NOT NULL DEFAULT 30,
  confirm_sla_hours    integer NOT NULL DEFAULT 4,
  daily_pallet_cap     integer CHECK (daily_pallet_cap > 0)
);
--> statement-breakpoint

CREATE TABLE carrier (
  id          serial PRIMARY KEY,
  name        text NOT NULL,
  vat_number  text UNIQUE,
  email       text NOT NULL,
  phone       text,
  status      carrier_status NOT NULL DEFAULT 'active',
  created_at  timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint

CREATE TABLE booking (
  id                 bigserial PRIMARY KEY,
  code               text NOT NULL UNIQUE,
  entity_id          integer NOT NULL REFERENCES entity (id),
  site_id            integer NOT NULL REFERENCES site (id),
  dock_id            integer NOT NULL REFERENCES dock (id),
  start_at           timestamptz NOT NULL,
  end_at             timestamptz NOT NULL,
  period             tstzrange GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
  status             booking_status NOT NULL DEFAULT 'requested',
  carrier_id         integer REFERENCES carrier (id),
  carrier_name       text NOT NULL,
  contact_email      text NOT NULL,
  driver_name        text,
  driver_phone       text,
  plate              text,
  vehicle_type       vehicle_type NOT NULL,
  pallets            integer NOT NULL DEFAULT 0 CHECK (pallets >= 0),
  parcels            integer NOT NULL DEFAULT 0 CHECK (parcels >= 0),
  supplier           text,
  order_no           text,
  ddt_no             text,
  notes              text,
  manage_token_hash  text NOT NULL,
  anonymized_at      timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  CHECK (end_at > start_at),
  -- The core guarantee: no two live bookings may overlap on the same dock.
  -- 'requested' is included so a slot is held while the warehouse confirms it.
  CONSTRAINT booking_no_overlap EXCLUDE USING gist (dock_id WITH =, period WITH &&)
    WHERE (status IN ('requested', 'confirmed', 'arrived', 'unloading'))
);
--> statement-breakpoint
CREATE INDEX booking_site_start_idx ON booking (site_id, start_at);
--> statement-breakpoint
CREATE INDEX booking_status_idx ON booking (status) WHERE status IN ('requested', 'confirmed');
--> statement-breakpoint

CREATE TABLE app_user (
  id             serial PRIMARY KEY,
  email          text NOT NULL UNIQUE,
  name           text NOT NULL,
  role           user_role NOT NULL,
  password_hash  text,
  totp_secret    text,
  is_active      boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint

-- Which sites a warehouse user may see. Admins see all sites regardless.
CREATE TABLE user_site (
  user_id  integer NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  site_id  integer NOT NULL REFERENCES site (id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, site_id)
);
--> statement-breakpoint

-- Audit trail of every status change.
CREATE TABLE booking_event (
  id             bigserial PRIMARY KEY,
  booking_id     bigint NOT NULL REFERENCES booking (id) ON DELETE CASCADE,
  from_status    booking_status,
  to_status      booking_status NOT NULL,
  actor_type     actor_type NOT NULL,
  actor_user_id  integer REFERENCES app_user (id),
  note           text,
  created_at     timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint
CREATE INDEX booking_event_booking_idx ON booking_event (booking_id);
--> statement-breakpoint

CREATE TABLE incident (
  id          bigserial PRIMARY KEY,
  booking_id  bigint NOT NULL REFERENCES booking (id) ON DELETE CASCADE,
  type        text NOT NULL,
  note        text,
  created_by  integer REFERENCES app_user (id),
  created_at  timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint

CREATE TABLE notification_log (
  id          bigserial PRIMARY KEY,
  booking_id  bigint REFERENCES booking (id) ON DELETE SET NULL,
  channel     text NOT NULL,
  template    text NOT NULL,
  recipient   text NOT NULL,
  ok          boolean NOT NULL,
  error       text,
  sent_at     timestamptz NOT NULL DEFAULT now()
);
