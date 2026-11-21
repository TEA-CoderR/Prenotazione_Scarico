-- A delivery that arrived but was turned away at the dock (damaged, wrong goods, no DDT…).
-- The reason is stored as an incident (type 'refused') and in the status event note.
ALTER TYPE booking_status ADD VALUE IF NOT EXISTS 'refused';
