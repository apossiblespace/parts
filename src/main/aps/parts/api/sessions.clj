(ns aps.parts.api.sessions
  "Session endpoints — out-of-band REST, not change-events (ADR-0014):
   each is a single-row write to the non-temporal sessions table, and the
   separate call is what guarantees a new Session's anchor commits before
   any content attributed to it. All routes sit under /maps/:id behind
   `wrap-map-access`, so missing and not-owned Maps 404 before we get here."
  (:require
   [aps.parts.entity.session :as session]
   [com.brunobonacci.mulog :as mulog]
   [ring.util.response :as response]))

(defn list-sessions
  "A Map's Sessions ordered by anchor, each with its activated Part id."
  [{{{:keys [id]} :path} :parameters :as _request}]
  (-> (response/response (session/index id))
      (response/status 200)))

(defn create-session
  "Open a new Session; the anchor is captured server-side now."
  [{:keys [identity] {{:keys [id]} :path} :parameters :as _request}]
  (let [created (session/create! id (:sub identity))]
    (mulog/log ::session-created :map-id id :user-id (:sub identity))
    (-> (response/response created)
        (response/status 201))))

(defn update-trigger
  "Set the active Session's trigger text (nil clears it)."
  [{:keys                           [identity body-params]
    {{:keys [id session-id]} :path} :parameters            :as _request}]
  (-> (response/response (session/update-trigger!
                          session-id id (:trigger body-params) (:sub identity)))
      (response/status 200)))

(defn delete-session
  "Delete the latest Session when empty — the started-by-mistake undo."
  [{:keys [identity] {{:keys [id session-id]} :path} :parameters :as _request}]
  (session/delete! session-id id (:sub identity))
  (response/status 204))

(defn set-activation
  "Link the Part this Session activated (one per Session at launch)."
  [{:keys                           [identity body-params]
    {{:keys [id session-id]} :path} :parameters            :as _request}]
  (-> (response/response (session/set-activation!
                          session-id id (:part_id body-params) (:sub identity)))
      (response/status 200)))

(defn clear-activation
  "Remove the Session's activated-Part link."
  [{:keys [identity] {{:keys [id session-id]} :path} :parameters :as _request}]
  (session/clear-activation! session-id id (:sub identity))
  (response/status 204))
