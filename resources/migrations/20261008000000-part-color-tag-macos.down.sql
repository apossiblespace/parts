-- Orange and yellow have no equivalent in the old palette; they clear.
ALTER TABLE parts
  DROP CONSTRAINT parts_color_tag_check;
--;;
UPDATE parts SET color_tag = NULL WHERE color_tag IN ('orange', 'yellow');
--;;
ALTER TABLE parts
  ADD CONSTRAINT parts_color_tag_check
    CHECK (color_tag IN ('red', 'green', 'blue', 'purple', 'pink'));
