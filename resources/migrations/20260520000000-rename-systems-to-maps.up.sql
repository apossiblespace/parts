-- Rename the "System" entity to "Map" (TASK-015).
--
-- This renames the tables, FK columns, indexes, constraints, and triggers
-- that carry "system" in their names to the "map" form. The audit trigger
-- function `audit_log_change` uses TG_TABLE_NAME, so it follows the table
-- rename automatically — no function change needed. Historical `audit_log`
-- rows store the table name as data; those are rewritten here too so the
-- audit trail is consistent with the new schema (pre-launch cleanliness).

-- Tables --------------------------------------------------------------------
ALTER TABLE systems RENAME TO maps;
--;;
ALTER TABLE system_metadata RENAME TO map_metadata;
--;;

-- FK / reference columns ----------------------------------------------------
ALTER TABLE parts RENAME COLUMN system_id TO map_id;
--;;
ALTER TABLE relationships RENAME COLUMN system_id TO map_id;
--;;
ALTER TABLE map_metadata RENAME COLUMN system_id TO map_id;
--;;

-- Primary-key constraint (kept its auto-generated name through the rename) ---
ALTER TABLE maps RENAME CONSTRAINT systems_pkey TO maps_pkey;
--;;

-- Indexes -------------------------------------------------------------------
ALTER INDEX systems_owner RENAME TO maps_owner;
--;;
ALTER INDEX parts_system_lookup RENAME TO parts_map_lookup;
--;;
ALTER INDEX relationships_system_lookup RENAME TO relationships_map_lookup;
--;;
ALTER INDEX system_metadata_id_lookup RENAME TO map_metadata_id_lookup;
--;;
ALTER INDEX system_metadata_system_lookup RENAME TO map_metadata_map_lookup;
--;;
ALTER INDEX system_metadata_current RENAME TO map_metadata_current;
--;;

-- Triggers (kept their names through the table rename) ----------------------
ALTER TRIGGER systems_audit ON maps RENAME TO maps_audit;
--;;
ALTER TRIGGER system_metadata_audit ON map_metadata RENAME TO map_metadata_audit;
--;;

-- Historical audit_log rows: rewrite the stored table-name data -------------
UPDATE audit_log SET table_name = 'maps'         WHERE table_name = 'systems';
--;;
UPDATE audit_log SET table_name = 'map_metadata' WHERE table_name = 'system_metadata';
