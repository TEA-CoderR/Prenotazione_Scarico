-- Store DETERSIVO lists made on the scanners: one shared list per store per week (all the store's
-- scanners add to it), editable until the weekly deadline. The purchasing program downloads them.
CREATE TABLE app_setting (
  key         text PRIMARY KEY,
  value       jsonb NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint
CREATE TABLE store_list (
  id            serial PRIMARY KEY,
  entity_code   smallint NOT NULL,
  week          date NOT NULL,
  deadline      timestamptz NOT NULL,
  status        text NOT NULL DEFAULT 'draft',
  submitted_at  timestamptz,
  submitted_by  text,
  received_at   timestamptz,
  feedback      jsonb,
  feedback_at   timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (entity_code, week)
);
--> statement-breakpoint
CREATE TABLE store_list_line (
  list_id     integer NOT NULL REFERENCES store_list(id) ON DELETE CASCADE,
  ean         text NOT NULL,
  name        text NOT NULL DEFAULT '',
  boxes       integer NOT NULL DEFAULT 0,
  kind        text NOT NULL DEFAULT 'griglia',
  reason      text,
  orig_ean    text,
  orig_name   text,
  updated_by  text,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (list_id, ean)
);
