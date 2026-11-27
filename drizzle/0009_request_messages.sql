-- The whole conversation of a product request (store <-> purchasing office), oldest first:
-- [{"by": "store" | "office", "text": "...", "at": "<iso>", "boxes"?: n, "photo"?: true, "kind"?: "<answer kind>"}].
-- Before this every new answer overwrote the previous one (note / answer / store_reply stay as the latest).
ALTER TABLE product_request ADD COLUMN messages jsonb NOT NULL DEFAULT '[]'::jsonb;
