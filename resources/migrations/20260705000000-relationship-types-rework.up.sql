-- Pre-launch relationship-type vocabulary rework: retire 'blended', add
-- 'activates', rename the remaining types. Production holds zero
-- relationship rows, so this is a constraint swap; the UPDATEs remap
-- dev/staging data (demo maps) so the new constraint can attach.

ALTER TABLE relationships DROP CONSTRAINT relationships_type_check;
--;;
UPDATE relationships SET type = 'protects' WHERE type = 'protective';
--;;
UPDATE relationships SET type = 'polarizes-with' WHERE type = 'polarization';
--;;
UPDATE relationships SET type = 'works-with' WHERE type = 'alliance';
--;;
UPDATE relationships SET type = 'carries-burden' WHERE type = 'burden';
--;;
UPDATE relationships SET type = 'unknown' WHERE type = 'blended';
--;;
ALTER TABLE relationships ADD CONSTRAINT relationships_type_check
  CHECK (type IN ('unknown', 'protects', 'polarizes-with', 'works-with', 'activates', 'carries-burden'));
