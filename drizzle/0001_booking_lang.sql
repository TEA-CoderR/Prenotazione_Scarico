-- Language the carrier used, so later e-mails (confirmation, cancellation) match it.
ALTER TABLE booking ADD COLUMN lang text NOT NULL DEFAULT 'it' CHECK (lang IN ('it', 'en'));
