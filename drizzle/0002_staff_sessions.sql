-- Login sessions for warehouse staff. Only a SHA-256 of the cookie token is stored.
CREATE TABLE staff_session (
  token_hash    text PRIMARY KEY,
  user_id       integer NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  created_at    timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL
);
--> statement-breakpoint
CREATE INDEX staff_session_user_idx ON staff_session (user_id);
