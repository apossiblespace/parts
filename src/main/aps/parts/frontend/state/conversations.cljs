(ns aps.parts.frontend.state.conversations
  "Pure state for Part conversations (ADR-0018): the optimistic entry
   list, the groupings the conversation window renders, and which mode
   the window shows.

   Entries arrive from the server in writing order, each tagged with the
   Session it first appeared in (`:first_appeared_ordinal`). The list is
   only ever appended to, so its order stays the writing order and no
   client-side sort is needed.

   Re-frame-free so the kaocha cljs suite can unit-test it."
  (:require
   [aps.parts.frontend.state.sessions :as sessions]))

(def ^:private entries-path [:map :conversation_entries])

(defn add-entry
  "Append a new entry, stamped with the active Session (a new entry first
   appears in it by construction — the same rule `stamp-first-appearance`
   applies to Parts)."
  [db entry]
  (update-in db entries-path (fnil conj [])
             (sessions/stamp-first-appearance db entry)))

(defn merge-entry
  "`entries` with entry `id` merged with `attrs`. Entry-vector fns (this
   and the two below) are shared by the optimistic handlers and the
   playground's storage, so both apply a change the same way."
  [entries id attrs]
  (mapv #(if (= (:id %) id) (merge % attrs) %) entries))

(defn remove-entry [entries id]
  (filterv #(not= (:id %) id) entries))

(defn remove-part-entries
  "A deleted Part takes its conversation with it (the server cascades the
   same way)."
  [entries part-id]
  (filterv #(not= (:part_id %) part-id) entries))

(defn by-session
  "`entries` grouped by the Session they appeared in, oldest first:
   `[[ordinal [entry …]] …]`. Demo Maps have no Sessions — everything
   lands in one group with a nil ordinal."
  [entries]
  (->> entries
       (group-by :first_appeared_ordinal)
       (sort-by key)
       vec))

(defn by-part
  "`entries` grouped by Part, in the order each Part first speaks:
   `[[part-id [entry …]] …]`."
  [entries]
  (let [groups (group-by :part_id entries)]
    (mapv (fn [pid] [pid (groups pid)])
          (distinct (map :part_id entries)))))

(defn runs
  "`[[speaker [entry …]] …]`: consecutive entries from one speaker."
  [entries]
  (mapv (fn [run] [(:speaker (first run)) run])
        (partition-by :speaker entries)))

(defn for-part [entries part-id]
  (filterv #(= (:part_id %) part-id) entries))

(defn speaker-label
  "Self, Therapist, or the Part's own label (read now, so a renamed Part
   relabels its past entries)."
  [speaker part]
  (case speaker
    "self"      "Self"
    "therapist" "Therapist"
    (or (:label part) "Part")))

(defn window-mode
  "What the conversation window shows: the Part's conversation when the
   user chose Part mode and exactly one Part is selected; otherwise Self
   mode — every Part, grouped by Part inside each Session. So an empty
   selection shows Self mode, and reselecting a Part returns to it."
  [chosen scope-part]
  (if (and (= chosen :part) scope-part) :part :self))
