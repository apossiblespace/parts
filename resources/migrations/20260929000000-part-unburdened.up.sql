-- Unburdened (CONTEXT.md): the Part has released the burdens it carried.
-- Existing rows (including all history) backfill as not unburdened --
-- correct for a newly recorded state.
ALTER TABLE parts
  ADD COLUMN unburdened boolean NOT NULL DEFAULT false;
