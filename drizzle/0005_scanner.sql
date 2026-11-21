-- Store scanners (Zebra TC22): one account per device, paired with a one-time code.
CREATE TABLE scanner_device (
  id               serial PRIMARY KEY,
  name             text NOT NULL,
  entity_code      smallint,
  token_hash       text UNIQUE,
  pair_code_hash   text UNIQUE,
  pair_expires_at  timestamptz,
  is_active        boolean NOT NULL DEFAULT true,
  created_at       timestamptz NOT NULL DEFAULT now(),
  paired_at        timestamptz,
  last_seen_at     timestamptz
);
--> statement-breakpoint
-- What the scanners look up: DETERSIVO Griglia, this week's wholesaler price lists, substitutes,
-- direct suppliers. Published by the purchasing program; the latest snapshot is what devices download.
CREATE TABLE catalog_snapshot (
  id          serial PRIMARY KEY,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  text NOT NULL,
  item_count  integer NOT NULL,
  payload     jsonb NOT NULL
);
--> statement-breakpoint
-- Large catalogs arrive in parts (reverse proxies limit the request size).
CREATE TABLE catalog_part (
  upload_id   text NOT NULL,
  part        integer NOT NULL,
  parts       integer NOT NULL,
  payload     jsonb NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (upload_id, part)
);
