-- No-op: the synthetic Session-1 rows are indistinguishable from organic
-- ones after further use, and the companion migration's down drops the
-- sessions table anyway.
SELECT 1;
