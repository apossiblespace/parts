-- Colour tag palette becomes the six macOS Finder tag colours. Pink is
-- gone; its rows (including history) move to purple, the nearest hue.
ALTER TABLE parts
  DROP CONSTRAINT parts_color_tag_check;
--;;
UPDATE parts SET color_tag = 'purple' WHERE color_tag = 'pink';
--;;
ALTER TABLE parts
  ADD CONSTRAINT parts_color_tag_check
    CHECK (color_tag IN ('red', 'orange', 'yellow', 'green', 'blue', 'purple'));
