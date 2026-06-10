(ns aps.parts.api.maps
  (:require
   [aps.parts.api.maps-events :as events]
   [aps.parts.db :as db]
   [aps.parts.entity.map :as parts-map]
   [aps.parts.export :as export]
   [aps.parts.render.document :as document]
   [aps.parts.render.pdf :as pdf]
   [aps.parts.render.preview :as preview]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [com.brunobonacci.mulog :as mulog]
   [jsonista.core :as json]
   [ring.util.response :as response])
  (:import
   (java.io ByteArrayInputStream)))

(defn- etag-for-map
  "Quoted ETag string derived from a Map's version timestamp. nil when
   the map has no version (shouldn't happen post-`create!`). Used by
   both Render handlers — the ETag value is the same regardless of
   output format; the browser keys cache entries by URL anyway."
  [map-id]
  (when-let [v (parts-map/version map-id)]
    (format "\"%d\"" (inst-ms v))))

(defn- not-modified
  [tag]
  {:status 304 :headers {"ETag" tag}})

(defn- safe-filename
  "Sanitise a user-supplied string for use in a `Content-Disposition`
   filename. Strips characters that would break the header (`\"`,
   `\\r`, `\\n`, path separators) and falls back to `map` when the
   result is empty. Does not handle non-ASCII via RFC 5987 — modern
   browsers display non-ASCII titles in the URL fallback, which is
   acceptable for the launch cohort."
  [s]
  (let [cleaned (some-> s (str/replace #"[\"\r\n/\\]" "") str/trim)]
    (if (str/blank? cleaned) "map" cleaned)))

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

(defn- json-safe
  "Make export data JSON-friendly: OffsetDateTime endpoints become ISO-8601
   strings (UUIDs jsonista already renders as strings)."
  [x]
  (walk/postwalk
   (fn [v] (if (instance? java.time.OffsetDateTime v) (str v) v))
   x))

(defn export-json
  "Export a Map's full valid-time history as a downloadable JSON file — the data
   subject's Art. 15 / 20 copy (ADR-0010). Access gated by `wrap-map-access`, so
   missing / not-owned both 404 before we get here."
  [{:keys [parameters] :as _request}]
  (let [map-id   (get-in parameters [:path :id])
        filename (str (safe-filename (:title (parts-map/fetch map-id))) ".json")
        body     (json/write-value-as-string
                  (json-safe (export/export-map db/datasource map-id)))]
    (-> (response/response body)
        (response/status 200)
        (response/header "Content-Type" "application/json")
        (response/header "Content-Disposition"
                         (str "attachment; filename=\"" filename "\"")))))

(defn preview-svg
  "Render a Map as a glanceable preview SVG for the Maps-list
   thumbnail. Access gated by `wrap-map-access`. See
   `aps.parts.render.preview` (basic circles + lines, monochrome).
   ETag/304 caches the response per Map version — see `etag-for-map`."
  [{:keys [parameters headers] :as _request}]
  (let [map-id (get-in parameters [:path :id])
        tag    (etag-for-map map-id)]
    (if (and tag (= tag (get headers "if-none-match")))
      (not-modified tag)
      (let [svg (preview/render (parts-map/fetch map-id))]
        (cond-> (-> (response/response svg)
                    (response/status 200)
                    (response/header "Content-Type" "image/svg+xml")
                    (response/header "Cache-Control"
                                     "private, max-age=60, must-revalidate"))
          tag (response/header "ETag" tag))))))

(defn render-pdf
  "Render a Map as a PDF — the document renderer's SVG transcoded via
   Apache FOP. Access gated by `wrap-map-access`. Same ETag/304 dance
   as `preview-svg`; PDF transcoding is the expensive step
   (hundreds of ms), so skipping it on unchanged Maps is the real win."
  [{:keys [parameters headers] :as _request}]
  (let [map-id (get-in parameters [:path :id])
        tag    (etag-for-map map-id)]
    (if (and tag (= tag (get headers "if-none-match")))
      (not-modified tag)
      (let [the-map  (parts-map/fetch map-id)
            filename (safe-filename (:title the-map))
            bytes    (pdf/svg->pdf (document/render the-map))]
        (cond-> (-> (response/response (ByteArrayInputStream. bytes))
                    (response/status 200)
                    (response/header "Content-Type" "application/pdf")
                    (response/header "Content-Length" (str (count bytes)))
                    ;; `no-cache` (not `max-age=60`) so the browser
                    ;; revalidates every Download click via the ETag —
                    ;; an edit-then-redownload flow inside the old 60-s
                    ;; window would otherwise serve a stale PDF.
                    ;; Unchanged Maps still return 304 cheaply.
                    (response/header "Cache-Control" "private, no-cache")
                    (response/header "Content-Disposition"
                                     (str "attachment; filename=\""
                                          filename ".pdf\"")))
          tag (response/header "ETag" tag))))))
