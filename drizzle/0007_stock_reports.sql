-- Stock errors reported by the stores from the scanners (the system says there is stock, the shelf is
-- empty / different). The purchasing office corrects the stock in the ERP and marks the report.
CREATE TABLE stock_report (
  id            serial PRIMARY KEY,
  entity_code   smallint NOT NULL,
  ean           text NOT NULL,
  name          text NOT NULL DEFAULT '',
  system_qty    numeric,
  counted_qty   integer NOT NULL,
  counted_by    text NOT NULL,
  device        text,
  status        text NOT NULL DEFAULT 'open',
  answer        text,
  handled_by    text,
  handled_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint
CREATE INDEX stock_report_store_ean ON stock_report (entity_code, ean);
