-- observations.vehicle_id is dropped: trajectory_events is the authoritative link
-- between an observation and a vehicle, and it always was.
--
-- The redundant column was actively harmful. Its foreign key to vehicles meant a
-- TRUNCATE ... CASCADE on vehicles -- the obvious way to clear resolved identities --
-- cascaded into observations and destroyed every ingested event. Removing the column
-- removes that edge from the FK graph entirely.
ALTER TABLE observations DROP COLUMN IF EXISTS vehicle_id;
