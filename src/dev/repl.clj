(ns repl
  (:require
   [parts.server :as server]
   [shadow.cljs.devtools.api :as shadow]
   [shadow.cljs.devtools.server :as shadow-server]))

(defonce server-ref (atom nil))

;; TODO: Do we also want to open Inspector automatically?
(defn start []
  (shadow-server/start!)
  (shadow/watch :frontend)

  (reset! server-ref
          (server/-main))
  ::started)

(defn stop []
  (when-some [stop-server @server-ref]
    (reset! server-ref nil)
    (stop-server))
  ::stopped)

(defn go []
  (stop)
  (start))

(defn cljs-repl []
  (shadow.cljs.devtools.api/repl :frontend))
