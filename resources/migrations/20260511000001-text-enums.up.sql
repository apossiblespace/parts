-- Convert part_type / relationship_type ENUMs back to TEXT + CHECK constraints,
-- matching the project's pre-existing convention. Clojure passes strings; the
-- ENUM requires explicit casts at every write, which is more friction than
-- type-safety benefit for our domain (a small, stable set of values).

ALTER TABLE parts        ALTER COLUMN type TYPE TEXT USING type::text;
--;;
ALTER TABLE relationships ALTER COLUMN type TYPE TEXT USING type::text;
--;;

ALTER TABLE parts ADD CONSTRAINT parts_type_check
  CHECK (type IN ('manager', 'firefighter', 'exile', 'unknown'));
--;;
ALTER TABLE relationships ADD CONSTRAINT relationships_type_check
  CHECK (type IN ('protective', 'polarization', 'alliance', 'burden', 'blended', 'unknown'));
--;;

DROP TYPE IF EXISTS part_type;
--;;
DROP TYPE IF EXISTS relationship_type;
