-- Product requests from the stores: the scanner found an item the store cannot order (unknown barcode,
-- removed from the Griglia, no wholesaler has it this week) and the store asks the purchasing office.
-- The office answers from the purchasing program (use another item / added to the Griglia / ordered this
-- week only / order it from the direct supplier / we do not sell it / send more information).
CREATE TABLE product_request (
  id              serial PRIMARY KEY,
  entity_code     smallint NOT NULL,
  ean             text NOT NULL,
  name            text NOT NULL DEFAULT '',
  kind            text NOT NULL DEFAULT 'unknown',
  reason          text NOT NULL,
  boxes           integer,
  note            text,
  photo           bytea,
  device          text,
  status          text NOT NULL DEFAULT 'open',
  answer_kind     text,
  answer          text,
  answer_ean      text,
  answer_name     text,
  answer_boxes    integer,
  added_list_id   integer,
  added_before    integer,
  handled_by      text,
  handled_at      timestamptz,
  store_reply     text,
  seen_at         timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint
CREATE INDEX product_request_store_ean ON product_request (entity_code, ean);
--> statement-breakpoint
CREATE INDEX product_request_status ON product_request (status);
