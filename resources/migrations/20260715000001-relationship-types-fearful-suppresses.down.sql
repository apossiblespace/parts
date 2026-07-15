-- Deliberately NOT remapping rows that use the retired types: this is
-- live clinical data (and bitemporal history shares the table), so the
-- ADD CONSTRAINT below fails loudly if any row uses them, leaving the
-- decision with the operator instead of silently rewriting records.

ALTER TABLE relationships DROP CONSTRAINT relationships_type_check;
--;;
ALTER TABLE relationships ADD CONSTRAINT relationships_type_check
  CHECK (type IN ('unknown', 'protects', 'polarizes-with', 'works-with', 'activates', 'carries-burden'));
