(ns aps.parts.entity.policy-acceptance
  "Onboarding policy acceptances: write-once evidence of which document
   versions a user agreed to at signup. Insert-only — never read to gate
   access (account existence does that). See ADR-0009."
  (:require
   [aps.parts.common.constants :as c]
   [aps.parts.db :as db]
   [aps.parts.legal :as legal]))

(defn- current-acceptances
  "The (document, version) pairs a signing-up user accepts: the current version
   of each legal document, from its front-matter. Versions are resolved here,
   server-side — never taken from the client, which only asserts agreement."
  []
  (mapv (fn [{:keys [slug]}]
          {:document slug :version (:version (legal/document slug))})
        c/legal-documents))

(defn record-onboarding!
  "Write one `policy_acceptances` row per accepted item for `user-id`, on the
   caller's transaction `tx` so it shares atomicity with account creation.
   Returns the inserted rows."
  [user-id tx]
  (mapv (fn [{:keys [document version]}]
          (db/insert! :policy_acceptances
                      {:user_id  (db/->uuid user-id)
                       :document document
                       :version  version}
                      tx))
        (current-acceptances)))
