(ns aps.parts.api.maps
  (:require
   [aps.parts.api.maps-events :as events]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.render.preview :as preview]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn list-maps
  "List all maps for the authenticated user"
  [{:keys [identity] :as _request}]
  (let [user-id (:sub identity)
        maps    (parts-map/index user-id)]
    (-> (response/response maps)
        (response/status 200))))

(defn create-map
  "Create a new map"
  [{:keys [identity body-params] :as _request}]
  (let [user-id  (:sub identity)
        map-data (assoc body-params :owner_id user-id)
        created  (parts-map/create! map-data user-id)]
    (-> (response/response created)
        (response/status 201))))

(defn get-map
  "Get a map by ID. Access is gated by `wrap-map-access` middleware."
  [{:keys [parameters] :as _request}]
  (let [map-id  (get-in parameters [:path :id])
        the-map (parts-map/fetch map-id)]
    (-> (response/response the-map)
        (response/status 200))))

(defn update-map
  "Update an existing map. Access is gated by `wrap-map-access` middleware."
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id (:sub identity)
        map-id  (get-in parameters [:path :id])
        updated (parts-map/update! map-id body-params user-id)]
    (-> (response/response updated)
        (response/status 200))))

(defn delete-map
  "Delete a map. Access is gated by `wrap-map-access` middleware."
  [{:keys [identity parameters] :as _request}]
  (let [user-id (:sub identity)
        map-id  (get-in parameters [:path :id])]
    (parts-map/delete! map-id user-id)
    (response/status 204)))

(defn process-changes
  "Apply a batch of changes for a map atomically. Access is gated by
   `wrap-map-access` middleware.

   Request body: a single change-event map, or a vector of them. Each has:
   - `entity`: the entity type (`part`, `relationship`)
   - `id`: the entity ID
   - `type`: the operation (`create`, `update`, `remove`)
   - `data`: operation-specific payload

   Atomicity is all-or-nothing: any failure rolls back the entire batch.

   Responses:
   - 200 OK + `{:success true :results [...]}` when every change applied
   - 404 Not Found when the Map doesn't exist or isn't owned by the caller
   - 422 Unprocessable + `{:error ... :failing_change <change>}` when a
     domain error rolled the batch back (handled by the exception middleware)
   - 409 Conflict for DB constraint violations
   - 500 for unexpected exceptions"
  [{:keys [identity parameters body-params] :as _request}]
  (let [user-id (:sub identity)
        map-id  (get-in parameters [:path :id])
        results (events/apply-changes!
                 db/datasource
                 {:map-id   (db/->uuid map-id)
                  :actor-id user-id
                  :changes  body-params})]
    (mulog/log ::process-changes
               :user-id      user-id
               :map-id       map-id
               :change-count (count results))
    (-> (response/response {:success true :results results})
        (response/status 200))))

(defn preview-svg
  "Render a Map as a glanceable preview SVG for the Maps-list thumbnail.
   Access gated by `wrap-map-access`. Calls into
   `aps.parts.render.preview` (basic circles + lines, brand teal,
   monochrome) — for the high-fidelity print artifact see
   `render.document` and `render-pdf` (ADR-0008).

   Emits an `ETag` computed from the Map's most recent change time
   (`entity.map/version` → `inst-ms` → quoted per RFC 7232), so a
   browser's next request can include `If-None-Match` and get 304
   without re-rendering. Caching state lives entirely in the browser.

   `inst-ms` works whether the JDBC driver hands back a
   `java.sql.Timestamp` (default) or a `java.time.OffsetDateTime`
   (if next.jdbc is later configured for java.time types) — both
   implement `clojure.core.protocols/Inst`."
  [{:keys [parameters headers] :as _request}]
  (let [map-id (get-in parameters [:path :id])
        tag    (when-let [v (parts-map/version map-id)]
                 (format "\"%d\"" (inst-ms v)))]
    (if (and tag (= tag (get headers "if-none-match")))
      {:status 304 :headers {"ETag" tag}}
      (let [svg (preview/render (parts-map/fetch map-id))]
        (cond-> (-> (response/response svg)
                    (response/status 200)
                    (response/header "Content-Type" "image/svg+xml")
                    (response/header "Cache-Control"
                                     "private, max-age=60, must-revalidate"))
          tag (response/header "ETag" tag))))))

(defn render-pdf
  "Render a Map as a PDF via Apache Batik SVG transcoding of the
   document renderer's output. Currently 501 — Batik wiring + document
   chrome (title, date, 'Made with Parts' footer) land in the next
   increment. See ADR-0008."
  [_request]
  (-> (response/response {:error "Not implemented"})
      (response/status 501)))
