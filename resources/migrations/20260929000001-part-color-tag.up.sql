-- Colour tag (CONTEXT.md): an optional therapist-defined category colour
-- from a fixed palette, stored by name so shades can be retuned without
-- rewriting history. NULL is "No colour tag". The CHECK mirrors
-- aps.parts.common.constants/color-tags; existing rows (and all history)
-- have no tag.
ALTER TABLE parts
  ADD COLUMN color_tag text
  CONSTRAINT parts_color_tag_check
    CHECK (color_tag IN ('red', 'green', 'blue', 'purple', 'pink'));
