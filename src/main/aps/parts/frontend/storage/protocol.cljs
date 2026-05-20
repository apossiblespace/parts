(ns aps.parts.frontend.storage.protocol)

(defprotocol StorageBackend
  "Protocol defining the interface for storage backends.
   All storage backends must implement these methods to handle
   map data persistence and retrieval."

  (list-maps [this]
    "Returns a channel that will contain a list of available maps.
     Each map should be a map with at least :id and :title keys.")

  (load-map [this map-id]
    "Returns a channel that will contain the full map data for the given ID.
     The map data should include all parts, relationships, and metadata.")

  (create-map [this map-data]
    "Creates a new map with the given data.
     Returns a channel that will contain the created map data.")

  (update-map [this map-id map-data]
    "Updates map metadata (title, viewport_settings, etc.) by ID.
     Returns a channel that will contain the updated map data.")

  (process-batched-changes [this map-id batch]
    "Processes a batch of change events for the given map ID.
     The batch is a collection of normalized change events.
     Returns a channel that will contain the processing result."))
