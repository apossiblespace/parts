(ns aps.parts.stats
  "Operator REPL tooling for fleet- and account-level figures — the helpers
   you reach for in the production REPL to answer \"what's going on with this
   account?\" and \"how are we doing overall?\".

   Mirrors `aps.parts.billing`: small helpers that `println` a readable
   report *and* return the same data as a map, so they're useful both at a
   glance and as a value to drill into.

   Operator workflow (production REPL):

     (user-stats! \"jane@example.com\")   ; one account, by email
     (user-stats! \"0000-…-uuid\")        ; or by id (e.g. an audit actor_id)
     (fleet-stats!)                       ; the whole fleet at a glance

   The Operator console reads the same figures through `fleet` and
   `user-activity`, which return data and print nothing.

   Two definitions are shared with the rest of the system rather than
   redefined here:
   - billing standing comes from `billing/account-standing`.
   - 'currently exists' for the bitemporal tables comes from
     `bt/count-current` — this namespace never touches temporal SQL itself
     (enforced by the architecture-fitness test).

   Queries on `audit_log` select only `actor_id`, `occurred_at`,
   `table_name`, `operation`, the `id` key of `row_pk` and the `map_id` key
   of a row snapshot. The snapshots hold
   clinical text, and the console must show counts and timestamps only.
   See 'Operator console' in `CONTEXT.md`."
  (:require
   [aps.parts.billing :as billing]
   [aps.parts.db :as db]
   [aps.parts.db.bitemporal :as bt]
   [aps.parts.db.erasure :as erasure]
   [honey.sql.pg-ops])
  (:import
   (java.time LocalDate ZoneOffset)
   (java.util UUID)))

;; -- Per-user --------------------------------------------------------------

(defn- ->uuid-or-nil
  "Parse `x` as a UUID, or nil if it isn't one. An email never parses, so this
   cleanly routes `user-stats!` between an id lookup and an email lookup."
  [x]
  (try (UUID/fromString (str x)) (catch Exception _ nil)))

(defn- find-user
  "The `users` row for an email or id, with the columns the report needs, or
   nil if no such account."
  [email-or-id]
  (let [uuid (->uuid-or-nil email-or-id)]
    (db/query-one
     (db/sql-format
      {:select [:id :email :display_name :created_at
                :is_founding_circle :paid_through_date]
       :from   [:users]
       :where  (if uuid [:= :id uuid] [:= :email email-or-id])}))))

(defn- owner-maps
  "Subquery selecting the ids of the User's currently-existing Maps. Used to
   scope the bitemporal Part/Relationship counts to one owner — Parts carry
   only a `map_id`, never an owner."
  [owner-id]
  {:select [:id]
   :from   [:maps]
   :where  [:and [:= :owner_id owner-id] [:= :deleted_at nil]]})

(defn- count-maps
  "Current Maps (soft-delete: `deleted_at IS NULL`) matching optional `where`.
   Counted off the `maps` identity table, not the bitemporal layer. nil `where`
   counts the whole fleet's Maps."
  [where]
  (-> (db/query-one
       (db/sql-format
        {:select [[[:count :*] :c]]
         :from   [:maps]
         :where  (into [:and [:= :deleted_at nil]] (when where [where]))}))
      :c))

(defn- last-active
  "Most recent moment `actor-id` made a change — `MAX(audit_log.occurred_at)`
   — or nil if they never have. The single activity signal (see CONTEXT.md,
   'Active user'): edits, not app opens. Returned as an `OffsetDateTime` (UTC)
   — JDBC hands a `timestamptz` back as a `java.sql.Timestamp`, which we don't
   want to leak to the operator."
  [actor-id]
  (when-let [ts (:t (db/query-one
                     (db/sql-format
                      {:select [[[:max :occurred_at] :t]]
                       :from   [:audit_log]
                       :where  [:= :actor_id actor-id]})))]
    (-> ^java.sql.Timestamp ts .toInstant (.atOffset ZoneOffset/UTC))))

(defn- counts-for [owner-id]
  (let [maps (owner-maps owner-id)]
    {:maps          (count-maps [:= :owner_id owner-id])
     :parts         (bt/count-current db/datasource :parts [:in :map_id maps])
     :relationships (bt/count-current db/datasource :relationships [:in :map_id maps])}))

(defn- yes-no [b] (if b "yes" "no"))

(defn- fmt-billing [{:keys [status days_remaining]}]
  (case status
    :paid       (str "paid (" days_remaining " days left)")
    :overdue    (str "overdue (" (- days_remaining) " days)")
    :never-paid "never-paid"))

(defn- print-user-report
  [{:keys [id email display_name created_at is_founding_circle
           billing last_active counts]}]
  (println (format "%s  (%s)" email display_name))
  (println (format "  id            %s" id))
  (println (format "  created       %s" created_at))
  (println (format "  founding      %s" (yes-no is_founding_circle)))
  (println (format "  billing       %s" (fmt-billing billing)))
  (println (format "  last active   %s" (or last_active "never")))
  (println (format "  maps %d   parts %d   relationships %d"
                   (:maps counts) (:parts counts) (:relationships counts))))

(defn user-stats!
  "Print and return a single account's standing. `email-or-id` is an email or
   a user id (auto-detected). Returns nil — printing 'No account found' — when
   no account matches.

   The returned map:

     {:id :email :display_name :created_at :is_founding_circle
      :billing     {:status :paid_through_date :days_remaining}  ; billing/account-standing
      :last_active <OffsetDateTime or nil>
      :counts      {:maps :parts :relationships}}                ; current rows only"
  [email-or-id]
  (if-let [u (find-user email-or-id)]
    (let [report (assoc (select-keys u [:id :email :display_name
                                        :created_at :is_founding_circle])
                        :billing     (billing/account-standing u)
                        :last_active (last-active (:id u))
                        :counts      (counts-for (:id u)))]
      (print-user-report report)
      report)
    (do (println (str "No account found for " email-or-id))
        nil)))

;; -- Fleet -----------------------------------------------------------------

(defn- fleet-users
  "Every real account (tombstone excluded), with just the columns the fleet
   figures need. The fleet is small (concierge launch), so totals, founding,
   pending and the billing breakdown are all folded from this one pass rather
   than several COUNT queries; the heavier per-entity counts stay in SQL."
  []
  (db/query
   (db/sql-format
    {:select [:paid_through_date :is_founding_circle :deletion_requested_at]
     :from   [:users]
     :where  (erasure/exclude-tombstone :id)})))

(defn- billing-breakdown
  "Returns the counts of billing standing across `users` on `today`. It
   uses `billing/account-standing`, so paid, overdue and never-paid have
   one definition."
  [users today]
  (let [f (frequencies (map #(:status (billing/account-standing % today)) users))]
    {:paid       (get f :paid 0)
     :overdue    (get f :overdue 0)
     :never_paid (get f :never-paid 0)}))

(def activity-days
  "Number of UTC calendar days, today included, in the activity window of
   `user-activity` and of the 30-day figure of `fleet`."
  30)

(defn- window-start
  "Returns the first instant of the activity window that ends on `today`."
  [^LocalDate today]
  (-> today (.minusDays (dec activity-days)) .atStartOfDay (.atOffset ZoneOffset/UTC)))

(defn- in-window
  [start]
  [:and
   [:>= :occurred_at start]
   (erasure/exclude-tombstone :actor_id)])

(defn- rolling
  "Returns a HoneySQL expression for the instant `interval` before now.
   `interval` is a Postgres interval literal body, such as \"7 days\"."
  [interval]
  [:- [:now] [:cast interval :interval]])

(defn- active-count
  "Returns the number of distinct Users, tombstone excluded, with a change
   at or after `start`."
  [start]
  (-> (db/query-one
       (db/sql-format
        {:select [[[:count [:distinct :actor_id]] :c]]
         :from   [:audit_log]
         :where  (in-window start)}))
      :c))

(defn- pct
  "`count` as a percentage of `total`, rounded to one decimal. 0.0 when the
   fleet is empty (no division by zero)."
  [count total]
  (if (zero? total)
    0.0
    (/ (Math/round (* (/ count (double total)) 1000.0)) 10.0)))

(defn- print-fleet-report
  [{:keys [users active totals founding_circle billing]}]
  (println (format "Fleet: %d users  (%d pending deletion,  %d founding)"
                   (:total users) (:pending_deletion users) founding_circle))
  (println (format "  active 24h    %d  (%.1f%%)"
                   (-> active :last_24h :count) (-> active :last_24h :pct)))
  (println (format "  active 7d     %d  (%.1f%%)"
                   (-> active :last_7d :count) (-> active :last_7d :pct)))
  (println (format "  active 30d    %d  (%.1f%%)"
                   (-> active :last_30d :count) (-> active :last_30d :pct)))
  (println (format "  totals        maps %d   parts %d   relationships %d"
                   (:maps totals) (:parts totals) (:relationships totals)))
  (println (format "  billing       paid %d   overdue %d   never-paid %d"
                   (:paid billing) (:overdue billing) (:never_paid billing))))

(defn fleet
  "Returns the whole fleet at a glance.

     {:users   {:total :pending_deletion}        ; tombstone excluded; pending still counted
      :active  {:last_24h {:count :pct}           ; distinct actors in a rolling window
                :last_7d  {:count :pct}
                :last_30d {:count :pct}}          ; the `user-activity` window
      :totals  {:maps :parts :relationships}      ; current rows across all owners
      :founding_circle <n>
      :billing {:paid :overdue :never_paid}}      ; reuses billing/account-standing

   'Active' means *made a change* in the window (see CONTEXT.md, 'Active
   user') — edits, not app opens. Purged accounts have no `users` row and
   their past activity is re-attributed to the tombstone, so they fall out
   of every figure automatically. `today` is the UTC date that the 30-day
   window and the billing standing use."
  ([] (fleet (LocalDate/now ZoneOffset/UTC)))
  ([^LocalDate today]
   (let [users  (fleet-users)
         total  (count users)
         active (fn [start]
                  (let [n (active-count start)]
                    {:count n :pct (pct n total)}))]
     {:users           {:total            total
                        :pending_deletion (count (filter :deletion_requested_at users))}
      :active          {:last_24h (active (rolling "24 hours"))
                        :last_7d  (active (rolling "7 days"))
                        :last_30d (active (window-start today))}
      :totals          {:maps          (count-maps nil)
                        :parts         (bt/count-current db/datasource :parts)
                        :relationships (bt/count-current db/datasource :relationships)}
      :founding_circle (count (filter :is_founding_circle users))
      :billing         (billing-breakdown users today)})))

(defn fleet-stats!
  "Prints the `fleet` report and returns it."
  []
  (doto (fleet) print-fleet-report))

;;; Per-user activity

(defn- active-dates
  "Returns a map of actor id to the set of UTC dates on which the actor made
   a change since `start`."
  [start]
  (->> (db/query
        (db/sql-format
         {:select-distinct [:actor_id [[:cast [:timezone "UTC" :occurred_at] :date] :day]]
          :from            [:audit_log]
          :where           (in-window start)}))
       (reduce (fn [m {:keys [actor_id ^java.sql.Date day]}]
                 (update m actor_id (fnil conj #{}) (.toLocalDate day)))
               {})))

(defn- count-where [where]
  [:filter [:count :*] {:where where}])

(defn- change-counts
  "Returns a map of actor id to the change counts since `start`.

   A change to a `maps` row has no `map_id` key, because the row is the Map.
   Its `row_pk` id is the Map id."
  [start]
  (let [map-id [:coalesce
                [:->> :after_row "map_id"]
                [:->> :before_row "map_id"]
                [:case [:= :table_name "maps"] [:->> :row_pk "id"]]]]
    (->> (db/query
          (db/sql-format
           {:select   [:actor_id
                       [[:count [:distinct map-id]] :maps_edited]
                       [(count-where [:and [:= :table_name "sessions"] [:= :operation "I"]])
                        :sessions_started]
                       [(count-where [:= :table_name "parts"]) :parts]
                       [(count-where [:= :table_name "relationships"]) :relationships]
                       [(count-where [:= :table_name "conversation_entries"])
                        :conversation_entries]]
            :from     [:audit_log]
            :where    (in-window start)
            :group-by [:actor_id]}))
         (into {} (map (juxt :actor_id #(dissoc % :actor_id)))))))

(defn- last-active-by-actor
  "Returns a map of actor id to the moment of the actor's latest change."
  []
  (->> (db/query
        (db/sql-format
         {:select   [:actor_id [[:max :occurred_at] :t]]
          :from     [:audit_log]
          :where    (erasure/exclude-tombstone :actor_id)
          :group-by [:actor_id]}))
       (into {} (map (fn [{:keys [actor_id ^java.sql.Timestamp t]}]
                       [actor_id (-> t .toInstant (.atOffset ZoneOffset/UTC))])))))

(defn- count-by-owner
  "Returns a map of owner id to the row count of `q`, over current Maps only.
   `q` gives the `:from` and any `:join`, and must include `maps`."
  [q]
  (->> (db/query
        (db/sql-format
         (merge {:select   [:maps.owner_id [[:count :*] :c]]
                 :where    [:= :maps.deleted_at nil]
                 :group-by [:maps.owner_id]}
                q)))
       (into {} (map (juxt :owner_id :c)))))

(defn user-activity
  "Returns one map per real User, most recently active first, with the
   activity of the `activity-days` UTC days that end on `today`.

     {:id :email :display_name :created_at :is_founding_circle
      :pending_deletion <bool>
      :billing     {:status :paid_through_date :days_remaining}
      :last_active <OffsetDateTime or nil>   ; latest change, all time
      :active_days #{LocalDate}               ; inside the window only
      :counts      {:maps :sessions                ; current, all time
                    :maps_edited :sessions_started ; inside the window
                    :parts :relationships :conversation_entries}}"
  ([] (user-activity (LocalDate/now ZoneOffset/UTC)))
  ([^LocalDate today]
   (let [start    (window-start today)
         dates    (active-dates start)
         changes  (change-counts start)
         last-at  (last-active-by-actor)
         maps     (count-by-owner {:from [:maps]})
         sessions (count-by-owner {:from [:sessions]
                                   :join [:maps [:= :maps.id :sessions.map_id]]})
         zeroes   {:maps_edited   0 :sessions_started     0 :parts 0
                   :relationships 0 :conversation_entries 0}
         users    (db/query
                   (db/sql-format
                    {:select [:id :email :display_name :created_at
                              :is_founding_circle :paid_through_date
                              :deletion_requested_at]
                     :from   [:users]
                     :where  (erasure/exclude-tombstone :id)}))]
     (->> users
          (map (fn [{:keys [id] :as u}]
                 (-> (select-keys u [:id :email :display_name :created_at
                                     :is_founding_circle])
                     (assoc :pending_deletion (some? (:deletion_requested_at u))
                            :billing          (billing/account-standing u today)
                            :last_active      (last-at id)
                            :active_days      (get dates id #{})
                            :counts           (merge zeroes
                                                     (changes id)
                                                     {:maps     (get maps id 0)
                                                      :sessions (get sessions id 0)})))))
          (sort-by :last_active #(compare %2 %1))))))
