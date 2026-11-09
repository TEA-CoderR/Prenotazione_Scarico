-- Two-factor authentication (TOTP) for staff, and an audit trail of admin changes.
ALTER TABLE app_user ADD COLUMN totp_pending_secret text;
--> statement-breakpoint
ALTER TABLE app_user ADD COLUMN totp_last_step bigint;
--> statement-breakpoint
-- A session becomes fully usable only after the second factor (or its first-time setup).
-- Sessions created before this migration must go through it too.
ALTER TABLE staff_session ADD COLUMN mfa_verified boolean NOT NULL DEFAULT false;
--> statement-breakpoint
CREATE TABLE audit_log (
  id          bigserial PRIMARY KEY,
  user_id     integer REFERENCES app_user (id) ON DELETE SET NULL,
  action      text NOT NULL,
  target      text,
  details     jsonb,
  created_at  timestamptz NOT NULL DEFAULT now()
);
--> statement-breakpoint
CREATE INDEX audit_log_created_idx ON audit_log (created_at DESC);
