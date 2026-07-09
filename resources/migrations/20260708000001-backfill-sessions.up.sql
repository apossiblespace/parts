-- Make the Session derivation total for pre-existing Maps (ADR-0014, "The
-- derivation is total"): every Map with content gets a synthetic "Session 1"
-- anchored at its earliest content valid_at — MIN over ALL history rows,
-- retracted included (first appearance is a fact about the record), and
-- exact MIN is safe because the range's lower bound is inclusive.
-- Truly-empty Maps get no Session; their first arrives with their first
-- edit (edit-requires-a-Session, TASK-073.02). Production is zero-data:
-- this backfills dev/staging demo Maps.

INSERT INTO sessions (map_id, ordinal, trigger, anchor_valid_at)
SELECT map_id, 1, NULL, MIN(first_at)
FROM (
  SELECT map_id, lower(valid_at) AS first_at FROM parts
  UNION ALL
  SELECT map_id, lower(valid_at) AS first_at FROM relationships
) AS content
GROUP BY map_id;
