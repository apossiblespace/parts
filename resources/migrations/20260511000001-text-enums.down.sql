-- Restore ENUMs. The bitemporal rewrite's original up migration is the
-- authoritative source of those types; this down repeats their creation
-- so the rollback chain stays clean.
CREATE TYPE part_type AS ENUM ('manager', 'firefighter', 'exile', 'unknown');
--;;
CREATE TYPE relationship_type AS ENUM ('protective', 'polarization', 'alliance', 'burden', 'blended', 'unknown');
--;;

ALTER TABLE parts        DROP CONSTRAINT IF EXISTS parts_type_check;
--;;
ALTER TABLE relationships DROP CONSTRAINT IF EXISTS relationships_type_check;
--;;

ALTER TABLE parts        ALTER COLUMN type TYPE part_type USING type::part_type;
--;;
ALTER TABLE relationships ALTER COLUMN type TYPE relationship_type USING type::relationship_type;
