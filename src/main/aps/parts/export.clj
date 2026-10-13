(ns aps.parts.export
  "Builds a Map's data-subject export — the machine-readable copy of a Map's
   full valid-time history, for GDPR Art. 15 / 20 and the ToS 'export your Maps
   at any time' promise (ADR-0010).

   Composes `db.bitemporal/history`; it holds no temporal SQL of its own, so the
   temporal vocabulary stays quarantined behind that reader's
   `valid_from` / `valid_to` surface."
  (:require
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.entity.session :as session]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [jsonista.core :as json]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs])
  (:import
   (java.io ByteArrayOutputStream)
   (java.text BreakIterator Normalizer Normalizer$Form)
   (java.time OffsetDateTime)
   (java.util Locale)
   (java.util.zip ZipEntry ZipOutputStream)))

(def format-version
  "Bumped when the export shape changes incompatibly — lets a future importer
   detect the format (ADR-0010)."
  "1")

(def ^:private exported-columns
  "Per-entity allowlists of exported keys (ADR-0010 data minimization). A
   column added to one of these tables is NOT exported until deliberately
   named here — so controller metadata and any future internal/sensitive
   field stay out by default, rather than leaking on the day the column
   lands. The export's REQUIRED clinical fields (notes, body_location,
   trigger) are all present."
  {:parts                [:type :label :description :notes :position_x :position_y
                          :width :height :body_location :unburdened :color_tag
                          :valid_from :valid_to]
   :relationships        [:type :source_id :target_id :notes :intensity
                          :valid_from :valid_to]
   :conversation_entries [:part_id :speaker :text :valid_from :valid_to]
   :map-metadata         [:title :valid_from :valid_to]
   :sessions             [:id :ordinal :trigger :anchor_valid_at :activated_part_id]})

(defn- project
  [entity row]
  (select-keys row (exported-columns entity)))

(defn- by-entity
  "Group history versions by entity id into `[{:id … :versions […]}]`, earliest
   entity first. Each version is projected through `exported-columns`."
  [entity versions]
  (->> versions
       (group-by :id)
       (map (fn [[id vs]]
              {:id id :versions (mapv #(project entity %) vs)}))
       (sort-by (comp :valid_from first :versions))
       vec))

(defn- map-identity
  "The Map's non-temporal identity for the export: `id`, `created_at` and
   `archived_at` (nil for an active Map). `owner_id` / `actor_id` /
   `deleted_at` are controller metadata, excluded per ADR-0010."
  [ds map-id]
  (jdbc/execute-one! ds
                     ["SELECT id, created_at, archived_at FROM maps WHERE id = ?::uuid" (str map-id)]
                     {:builder-fn rs/as-unqualified-maps}))

(defn export-map
  "The full export for one Map as a plain data structure (ADR-0010 shape).
   Owner-scoping is the caller's responsibility (`wrap-map-access`); this assumes
   the Map exists and is the subject's to export."
  [ds map-id]
  (let [mid (db/->uuid map-id)]
    {:format_version       format-version
     :exported_at          (OffsetDateTime/now)
     :map                  (assoc (map-identity ds mid)
                                  :title_history
                                  (mapv #(project :map-metadata %)
                                        (bt/history ds :map_metadata [:= :map_id mid])))
     :parts                (by-entity :parts (bt/history ds :parts [:= :map_id mid]))
     :relationships        (by-entity :relationships
                                      (bt/history ds :relationships [:= :map_id mid]))
     ;; Clinical (ADR-0018). Additive key, so format_version stays "1".
     :conversation_entries (by-entity :conversation_entries
                                      (bt/history ds :conversation_entries
                                                  [:= :map_id mid]))
     ;; Sessions are non-temporal (ADR-0014): each exports once,
     ;; unversioned — the anchor instant is its valid-time place.
     :sessions             (mapv #(project :sessions %) (session/index ds mid))}))

;;; Files

(def ^:private max-filename-chars
  "Long enough for any real title; 60 characters stay under the 255-byte
   file name limit even in 4-byte UTF-8."
  60)

(defn- shorten
  "`s` cut to at most `n` code points, at a boundary between the
   characters a reader sees, so an emoji (a flag, a skin tone) or a letter
   with its accent is never split."
  [^String s n]
  (if (<= (.codePointCount s 0 (count s)) n)
    s
    (let [limit (.offsetByCodePoints s 0 n)
          chars (doto (BreakIterator/getCharacterInstance) (.setText s))]
      (subs s 0 (if (.isBoundary chars limit) limit (.preceding chars limit))))))

(defn safe-filename
  "Sanitise a user-supplied string for use as a file name, in a
   `Content-Disposition` header or a ZIP entry. Normalises it to NFC;
   strips control characters (which would break the header), the
   direction controls that can disguise a name, path separators, and the
   characters Windows refuses (`<>:\"|?*`); shortens it to
   `max-filename-chars`; drops leading dots (a hidden file); puts `_`
   before a name Windows reserves (CON, NUL, COM1, … also before a dot);
   and falls back to `map` when nothing is left. Joiners (ZWJ, ZWNJ) stay:
   Persian and Indic spelling and emoji sequences need them. Does not handle non-ASCII via
   RFC 5987 — modern browsers display non-ASCII titles in the URL
   fallback, which is acceptable for the launch cohort."
  [s]
  (let [cleaned (some-> s
                        (Normalizer/normalize Normalizer$Form/NFC)
                        (str/replace #"[\p{Cc}\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069<>:\"/\\|?*]" "")
                        (shorten max-filename-chars)
                        str/trim
                        (str/replace #"^\.+" "")
                        str/trim)]
    (cond
      (str/blank? cleaned)                                              "map"
      (re-matches #"(?i)(con|prn|aux|nul|com\d|lpt\d)(\..*)?" cleaned) (str "_" cleaned)
      :else                                                             cleaned)))

(defn- json-safe
  "Make export data JSON-friendly: OffsetDateTime endpoints become ISO-8601
   strings (UUIDs jsonista already renders as strings)."
  [x]
  (walk/postwalk
   (fn [v]
     (cond
       (instance? OffsetDateTime v) (str v)
       ;; A DATE column: write the day, not midnight in the JVM's zone.
       (instance? java.sql.Date v)  (str (.toLocalDate ^java.sql.Date v))
       :else                        v))
   x))

(defn- json-bytes [data]
  (.getBytes (json/write-value-as-string (json-safe data)) "UTF-8"))

(defn map-json
  "One Map's export (see `export-map`) as JSON bytes — the single-Map
   download and every Map file in the account ZIP."
  [ds map-id]
  (json-bytes (export-map ds map-id)))

;;; Account

(def ^:private account-columns
  "The account's own details in the export. An allowlist: Stripe ids,
   the unsubscribe token, the password hash and internal timestamps stay
   out, and a new column stays out until it is named here."
  [:email :display_name :role :created_at :is_founding_circle
   :paid_through_date
   [:stripe_subscription_status :subscription_status]
   [:stripe_plan :subscription_plan]
   :product_updates_chosen_at :product_updates_opted_out_at])

(defn- rows [ds query]
  (db/query ds (db/sql-format query)))

(defn- account-details
  "The account's own record, with the legal documents it accepted and
   the notices and product updates sent to it."
  [ds user-id]
  (let [uid (db/->uuid user-id)]
    (if-let [details (first (rows ds {:select account-columns
                                      :from   [:users]
                                      :where  [:= :id uid]}))]
      (assoc details
             :format_version     format-version
             :exported_at        (OffsetDateTime/now)
             :policy_acceptances (rows ds {:select   [:document :version :accepted_at]
                                           :from     [:policy_acceptances]
                                           :where    [:= :user_id uid]
                                           :order-by [:accepted_at]})
             :emails_sent        (rows ds {:select   [:e.kind :e.subject :d.sent_at]
                                           :from     [[:operator_email_deliveries :d]]
                                           :join     [[:operator_emails :e] [:= :e.id :d.email_id]]
                                           :where    [:and [:= :d.user_id uid] [:<> :d.sent_at nil]]
                                           :order-by [:d.sent_at]}))
      (throw (ex-info "User not found" {:type :not-found :id user-id})))))

(defn- fold
  "A name as a case-insensitive file system compares it."
  [^String s]
  (.toLowerCase s Locale/ROOT))

(defn- unique-names
  "`names` made unique by numbering repeats: \"Anna\", \"Anna (2)\", …
   A number is never one already taken (a Map may be titled \"Anna (2)\"),
   and case is ignored, as on macOS and Windows file systems."
  [names]
  (first (reduce (fn [[out used] n]
                   (let [free (->> (iterate inc 2)
                                   (map #(str n " (" % ")"))
                                   (cons n)
                                   (remove #(used (fold %)))
                                   first)]
                     [(conj out free) (conj used (fold free))]))
                 [[] #{}]
                 names)))

(defn- map-files
  "`[file-name map-id]` for each of the user's Maps, archived ones
   included, named from the current title."
  [ds user-id]
  (let [ids    (mapv :id (rows ds {:select   [:id]
                                   :from     [:maps]
                                   :where    [:and
                                              [:= :owner_id (db/->uuid user-id)]
                                              [:= :deleted_at nil]]
                                   :order-by [:created_at :id]}))
        titles (into {} (map (juxt :map_id :title))
                     (when (seq ids) (bt/as-of-now ds :map_metadata [:in :map_id ids])))]
    (map vector
         (unique-names (map #(safe-filename (titles %)) ids))
         ids)))

(defn account-zip
  "The user's whole export as ZIP bytes: `account.json` with the account
   details, and `maps/<title>.json` for each Map — each one the same as
   the single-Map export. Owner-scoping is the caller's responsibility."
  ;; ponytail: built in memory; fine for dozens of Maps, stream to the
  ;; response if accounts grow to hundreds of large Maps.
  [ds user-id]
  (let [bytes (ByteArrayOutputStream.)]
    ;; One snapshot, so the account file, the names and every Map file show
    ;; the same moment even if the user edits while the ZIP is built.
    (jdbc/with-transaction [tx ds {:isolation :repeatable-read :read-only true}]
      (with-open [zip (ZipOutputStream. bytes)]
        (doseq [[entry ^bytes content]
                (cons ["account.json" (json-bytes (account-details tx user-id))]
                      (for [[file-name map-id] (map-files tx user-id)]
                        [(str "maps/" file-name ".json") (map-json tx map-id)]))]
          (.putNextEntry zip (ZipEntry. ^String entry))
          (.write zip content)
          (.closeEntry zip))))
    (.toByteArray bytes)))
