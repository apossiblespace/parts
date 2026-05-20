-- Reverse the rename of the "Map" entity back to "System" (TASK-015).

-- Historical audit_log rows -------------------------------------------------
UPDATE audit_log SET table_name = 'system_metadata' WHERE table_name = 'map_metadata';
--;;
UPDATE audit_log SET table_name = 'systems'         WHERE table_name = 'maps';
--;;

-- Triggers ------------------------------------------------------------------
ALTER TRIGGER map_metadata_audit ON map_metadata RENAME TO system_metadata_audit;
--;;
ALTER TRIGGER maps_audit ON maps RENAME TO systems_audit;
--;;

-- Indexes -------------------------------------------------------------------
ALTER INDEX map_metadata_current RENAME TO system_metadata_current;
--;;
ALTER INDEX map_metadata_map_lookup RENAME TO system_metadata_system_lookup;
--;;
ALTER INDEX map_metadata_id_lookup RENAME TO system_metadata_id_lookup;
--;;
ALTER INDEX relationships_map_lookup RENAME TO relationships_system_lookup;
--;;
ALTER INDEX parts_map_lookup RENAME TO parts_system_lookup;
--;;
ALTER INDEX maps_owner RENAME TO systems_owner;
--;;

-- Primary-key constraint ----------------------------------------------------
ALTER TABLE maps RENAME CONSTRAINT maps_pkey TO systems_pkey;
--;;

-- FK / reference columns ----------------------------------------------------
ALTER TABLE map_metadata RENAME COLUMN map_id TO system_id;
--;;
ALTER TABLE relationships RENAME COLUMN map_id TO system_id;
--;;
ALTER TABLE parts RENAME COLUMN map_id TO system_id;
--;;

-- Tables --------------------------------------------------------------------
ALTER TABLE map_metadata RENAME TO system_metadata;
--;;
ALTER TABLE maps RENAME TO systems;
