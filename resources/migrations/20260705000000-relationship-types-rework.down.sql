-- Restore the previous relationship-type vocabulary. 'activates' has no
-- pre-rework equivalent, so it maps to 'unknown'; the 'blended' →
-- 'unknown' remap from the up-migration is not reversible.

ALTER TABLE relationships DROP CONSTRAINT relationships_type_check;
--;;
UPDATE relationships SET type = 'protective' WHERE type = 'protects';
--;;
UPDATE relationships SET type = 'polarization' WHERE type = 'polarizes-with';
--;;
UPDATE relationships SET type = 'alliance' WHERE type = 'works-with';
--;;
UPDATE relationships SET type = 'burden' WHERE type = 'carries-burden';
--;;
UPDATE relationships SET type = 'unknown' WHERE type = 'activates';
--;;
ALTER TABLE relationships ADD CONSTRAINT relationships_type_check
  CHECK (type IN ('protective', 'polarization', 'alliance', 'burden', 'blended', 'unknown'));
