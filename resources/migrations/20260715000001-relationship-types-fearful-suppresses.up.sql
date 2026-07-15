-- Extend the relationship-type vocabulary with 'fearful-of' and
-- 'suppresses'. Pure constraint swap: no existing rows change meaning.

ALTER TABLE relationships DROP CONSTRAINT relationships_type_check;
--;;
ALTER TABLE relationships ADD CONSTRAINT relationships_type_check
  CHECK (type IN ('unknown', 'protects', 'polarizes-with', 'works-with', 'activates', 'carries-burden', 'fearful-of', 'suppresses'));
