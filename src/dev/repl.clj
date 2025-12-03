(ns repl
  (:require
   [aps.parts.config :as conf]
   [aps.parts.db :as db]
   [aps.parts.server :as server]
   [kaocha.repl :as k]
   [kaocha.watch :as watch]
   [migratus.core :as migratus]
   [mulog-events]))

;; Helper functions to check and load dev dependencies
(defn- try-require
  "Try to require a namespace, returns true if successful"
  [ns-sym]
  (try
    (require ns-sym)
    true
    (catch Exception _
      false)))

(defn- portal-available?
  "Check if Portal is available"
  []
  (try-require 'portal.api))

(defn- shadow-available?
  "Check if Shadow-CLJS is available"
  []
  (and (try-require 'shadow.cljs.devtools.api)
       (try-require 'shadow.cljs.devtools.server)))

;; Initialize dev tools if available
(defonce ^:private dev-tools-initialized (atom false))

(defn- ensure-dev-tools
  "Initialize dev tools if available and not already done"
  []
  (when-not @dev-tools-initialized
    (when (portal-available?)
      ;; Initialize Portal
      (let [portal   (requiring-resolve 'portal.api/open)
            sessions (requiring-resolve 'portal.api/sessions)
            submit   (requiring-resolve 'portal.api/submit)]
        ;; Open portal window if no sessions exist
        (when (empty? (sessions))
          (portal {:app false}))
        ;; Add tap
        (add-tap submit)))
    (reset! dev-tools-initialized true)))

(defn cljs-repl []
  (if (shadow-available?)
    ((requiring-resolve 'shadow.cljs.devtools.api/repl) :frontend)
    (println "Shadow-CLJS not available. Add :dev alias to use CLJS REPL.")))

(defn portal-open
  "Open a new portal window in the browser"
  []
  (ensure-dev-tools)
  (if (portal-available?)
    ((requiring-resolve 'portal.api/open) {:app false})
    (println "Portal not available. Add :dev alias to use Portal.")))

(defn p
  "Submit value to Portal if available, otherwise just return it"
  [x]
  (ensure-dev-tools)
  (if (portal-available?)
    (do ((requiring-resolve 'portal.api/submit) x)
        x)
    x))

;; App server management

(defonce server-ref (atom nil))
(defonce postcss-process-ref (atom nil))

(defn- start-postcss-watch
  "Start PostCSS watch process"
  []
  (let [pb      (ProcessBuilder. ["pnpm" "exec" "postcss" "resources/styles/*.css" "-o" "resources/public/css/style.css" "--watch"])
        _       (.redirectErrorStream pb true)
        process (.start pb)
        reader  (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream process)))]
    (reset! postcss-process-ref process)
    (println "PostCSS watch started")
    ;; Stream output to REPL in background thread
    (future
      (try
        (loop []
          (when-let [line (.readLine reader)]
            (println "[PostCSS]" line)
            (recur)))
        (catch Exception _
          (when-not (.isAlive process)
            (println "[PostCSS] Process terminated")))))))

(defn- stop-postcss-watch
  "Stop PostCSS watch process"
  []
  (when-some [process @postcss-process-ref]
    (.destroy process)
    (reset! postcss-process-ref nil)
    (println "PostCSS watch stopped")))

(defn start []
  (ensure-dev-tools)
  (if (shadow-available?)
    (do
      ((requiring-resolve 'shadow.cljs.devtools.server/start!))
      ((requiring-resolve 'shadow.cljs.devtools.api/watch) :frontend))
    (println "Shadow-CLJS not available. Frontend compilation disabled."))

  (start-postcss-watch)

  (reset! server-ref
          (server/-main))
  ::started)

(defn stop []
  (when-some [stop-server @server-ref]
    (reset! server-ref nil)
    (stop-server))
  (stop-postcss-watch)
  ::stopped)

(defn go []
  (stop)
  (start))

(defn db-migrate
  "Migrate the database (uses current environment from PARTS_ENV)"
  {:exec-fn true} ; Mark this as an exec function for deps.edn
  [_] ; Accept args map from -X invocation
  (let [db-spec          (conf/database-config)
        migration-config (assoc db/migration-config :db db-spec)]
    (migratus/migrate migration-config)))

(defn db-rollback
  "Rollback the database (uses current environment from PARTS_ENV)"
  []
  (let [db-spec          (conf/database-config)
        migration-config (assoc db/migration-config :db db-spec)]
    (migratus/rollback migration-config)))

;; Test running
(defn with-env [env-map f]
  (let [old-env (into {} (System/getenv))]
    (try
      (doseq [[k v] env-map]
        (System/setProperty k v))
      (f)
      (finally
        ;; Restore original env vars
        (doseq [[k _] env-map]
          (if-let [old-val (get old-env k)]
            (System/setProperty k old-val)
            (System/clearProperty k)))))))

(defn run-tests
  "Run all tests or specific test(s).
   Examples:
   (run-tests)                    ;; runs current namespace
   (run-tests :unit)              ;; runs unit test suite
   (run-tests 'aps.parts.auth-test)   ;; runs specified namespace
   (run-tests #'aps.parts.auth-test/test-authenticate) ;; runs specific test"
  ([]
   (with-env {"PARTS_ENV" "test"}
     #(k/run)))
  ([& args]
   (with-env {"PARTS_ENV" "test"}
     #(apply k/run args))))

(defn run-all-tests
  "Run all test suites"
  []
  (k/run-all))

(defn watch-tests
  "Start test watcher - tests will re-run when files change"
  []
  (watch/run (k/config)))

(defn cljs-tests
  "Run ClojureScript tests"
  []
  (k/run :cljs))

(defn show-config
  "Show current test configuration"
  []
  (k/config))

(defn show-test-plan
  "Show current test plan"
  []
  (k/test-plan))
