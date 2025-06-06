(ns parts.frontend.storage.protocol)

(defprotocol StorageBackend
  "Protocol defining the interface for storage backends.
   All storage backends must implement these methods to handle
   system data persistence and retrieval."
  
  (list-systems [this]
    "Returns a channel that will contain a list of available systems.
     Each system should be a map with at least :id and :title keys.")
  
  (load-system [this system-id]
    "Returns a channel that will contain the full system data for the given ID.
     The system data should include all parts, relationships, and metadata.")
  
  (update-system [this system-id system-data]
    "Updates system metadata (title, viewport_settings, etc.) by ID.
     Returns a channel that will contain the updated system data.")
  
  (process-batched-changes [this system-id batch]
    "Processes a batch of change events for the given system ID.
     The batch is a collection of normalized change events.
     Returns a channel that will contain the processing result."))