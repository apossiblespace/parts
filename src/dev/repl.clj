(ns repl
  (:require
   [kaocha.repl :as k]
   [kaocha.watch :as watch]
   [migratus.core :as migratus]
   [portal.api :as portal]
   [mulog-events]
   [parts.config :as conf]
   [parts.db :as db]
   [parts.server :as server]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]))

(defn cljs-repl []
  (shadow.cljs.devtools.api/repl :frontend))

(def portal-instance
  "Open portal window if no portal sessions have been created.
   A portal session is created when opening a portal window.

   Opens in the default system browser."
  (or (seq (portal/sessions))
      (portal/open {:app false})))

(add-tap #'portal.api/submit)

(defn portal-open
  "Open a new portal window in the browser"
  []
  (portal/open {:app false}))

;; App server management

(defonce server-ref (atom nil))
(defonce postcss-process-ref (atom nil))

(defn- start-postcss-watch
  "Start PostCSS watch process"
  []
  (let [pb (ProcessBuilder. ["pnpm" "exec" "postcss" "resources/styles/*.css" "-o" "resources/public/css/style.css" "--watch"])
        _ (.redirectErrorStream pb true)
        process (.start pb)
        reader (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream process)))]
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
  (shadow-server/start!)
  (shadow/watch :frontend)
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
  "Migrate the database for ENV (default: development)"
  ([]
   (db-migrate :development))
  ([env]
   (let [db-spec {:dbtype "sqlite" :dbname (conf/database-file (conf/config env))}
         migration-config (assoc db/migration-config :db db-spec)]
     (migratus/migrate migration-config))))

(defn db-rollback
  "Rollback the database for ENV (default: development)"
  ([]
   (db-rollback :environment))
  ([env]
   (let [db-spec {:dbtype "sqlite" :dbname (conf/database-file (conf/config env))}
         migration-config (assoc db/migration-config :db db-spec)]
     (migratus/rollback migration-config))))

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
   (run-tests 'parts.auth-test)   ;; runs specified namespace
   (run-tests #'parts.auth-test/test-authenticate) ;; runs specific test"
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
