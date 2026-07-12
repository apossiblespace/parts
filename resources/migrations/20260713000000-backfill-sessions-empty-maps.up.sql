-- Close the gap the first backfill (20260708000001) left open: truly-empty
-- Maps got no Session then, on the premise that their first would arrive
-- with their first edit. That premise is withdrawn — a no-session Map must
-- not exist (Maps are now born with Session 1 at creation, and the only
-- Session refuses deletion). Anchor at now(): there is no content to
-- misattribute, and future content lands in Session 1's open-ended range.
INSERT INTO sessions (map_id, ordinal, trigger, anchor_valid_at)
SELECT m.id, 1, NULL, now()
FROM maps m
WHERE NOT EXISTS (SELECT 1 FROM sessions s WHERE s.map_id = m.id);
