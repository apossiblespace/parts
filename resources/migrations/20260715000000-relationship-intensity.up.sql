-- Intensity (CONTEXT.md): how strongly a Relationship's dynamic is
-- currently active, 0-100, rendered as jaggedness of the curve.
-- Existing rows (including all history) backfill at 0 -- the past reads
-- as calm, correct for a newly observed dimension.
ALTER TABLE relationships
  ADD COLUMN intensity double precision NOT NULL DEFAULT 0;
