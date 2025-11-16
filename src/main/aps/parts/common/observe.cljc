(ns aps.parts.common.observe
  #?(:clj
     (:require
      [aps.parts.config :as config]
      [clojure.string :as cstr]
      [com.brunobonacci.mulog :as mulog]
      [lambdaisland.config :as l-config])))

;; =============================================================================
;; Shared: Log Levels and Filtering

(def ^:private log-levels
  {:debug  0
   :info   1
   :warn   2
   :error  3
   :silent 4})

(defn- get-initial-log-level
  "Determine initial log level from configuration or build environment"
  []
  #?(:clj
     ;; Clojure: Read from lambdaisland/config, fallback to :warn
     (let [config-level (l-config/get config/config :log-level)]
       (if (and config-level (contains? log-levels config-level))
         config-level
         :warn))

     :cljs
     ;; ClojureScript: Read from DOM data attribute or goog.DEBUG
     (let [root-el        (when (exists? js/document)
                            (js/document.getElementById "root"))
           data-level     (when root-el (.getAttribute root-el "data-log-level"))
           explicit-level (when data-level (keyword data-level))]
       (cond
         (and explicit-level
              (contains? log-levels explicit-level)) explicit-level
         goog.DEBUG                                  :debug
         :else                                       :warn))))

(def ^:private current-log-level
  (atom (get-initial-log-level)))

(defn set-log-level!
  "Set the current log level. Valid levels: :debug :info :warn :error :silent"
  [level]
  (when (contains? log-levels level)
    (reset! current-log-level level)))

(defn get-log-level
  "Get the current log level"
  []
  @current-log-level)

(defn- should-log?
  [level]
  (>= (log-levels level) (log-levels @current-log-level)))

;; =============================================================================
;; Platform-specific: Logging Configuration

#?(:clj
   (do
     (defn- get-log-output-modes
       "Get enabled log output modes from config.
       Returns a set of enabled modes: #{:simple :mulog}
       Defaults to #{:simple} if not configured."
       []
       (let [modes (l-config/get config/config :log-output-modes)]
         (if (and modes (set? modes))
           modes
           #{:simple})))

     (defn- format-plain-message
       "Format a plain text log message with level prefix and namespace"
       [namespace level args]
       (let [level-str (str "[" (cstr/upper-case (name level)) "]")
             ns-str    (str "[" namespace "]")]
         (str level-str " " ns-str " " (cstr/join " " (map pr-str args)))))))

;; =============================================================================
;; Platform-specific: Logging Functions

#?(:cljs
   (do
     (def ^:private level-colors
       {:debug "color: #6b7280; font-weight: normal;" ; gray
        :info  "color: #2563eb; font-weight: bold;"   ; blue
        :warn  "color: #d97706; font-weight: bold;"   ; orange
        :error "color: #dc2626; font-weight: bold;"}) ; red

     (defn- format-message
       [namespace level & args]
       (let [level-style (level-colors level)
             prefix      (str "%c[" (name level) "]%c [" namespace "]")]
         (concat [prefix level-style ""] args)))))

(defn debug
  "Log debug message with namespace context"
  [namespace & args]
  (when (should-log? :debug)
    #?(:clj
       (let [modes (get-log-output-modes)]
         (when (:simple modes)
           (println (format-plain-message namespace :debug args)))
         (when (:mulog modes)
           (mulog/log ::debug
                      :namespace namespace
                      :message (cstr/join " " (map pr-str args)))))

       :cljs
       (apply js/console.log (format-message namespace :debug args)))))

(defn info
  "Log info message with namespace context"
  [namespace & args]
  (when (should-log? :info)
    #?(:clj
       (let [modes (get-log-output-modes)]
         (when (:simple modes)
           (println (format-plain-message namespace :info args)))
         (when (:mulog modes)
           (mulog/log ::info
                      :namespace namespace
                      :message (cstr/join " " (map pr-str args)))))

       :cljs
       (apply js/console.info (format-message namespace :info args)))))

(defn warn
  "Log warning message with namespace context"
  [namespace & args]
  (when (should-log? :warn)
    #?(:clj
       (let [modes (get-log-output-modes)]
         (when (:simple modes)
           (println (format-plain-message namespace :warn args)))
         (when (:mulog modes)
           (mulog/log ::warn
                      :namespace namespace
                      :message (cstr/join " " (map pr-str args)))))

       :cljs
       (apply js/console.warn (format-message namespace :warn args)))))

(defn error
  "Log error message with namespace context"
  [namespace & args]
  (when (should-log? :error)
    #?(:clj
       (let [modes (get-log-output-modes)]
         (when (:simple modes)
           (binding [*out* *err*]
             (println (format-plain-message namespace :error args))))
         (when (:mulog modes)
           (mulog/log ::error
                      :namespace namespace
                      :message (cstr/join " " (map pr-str args)))))

       :cljs
       (apply js/console.error (format-message namespace :error args)))))

;; =============================================================================
;; Event Tracking

#_{:clj-kondo/ignore [:unused-binding]}
(defn track
  "Track an event NAME with PROPS.
  On ClojureScript: sends to Plausible analytics if available.
  On Clojure: no-op."
  [name props]
  #?(:clj nil ; no-op on backend
     :cljs
     (when (js/window.plausible)
       (js/window.plausible name
                            (clj->js {:props props})))))

(comment
  ;; =============================================================================
  ;; Usage Examples

  ;; Runtime log level control (both platforms)
  (set-log-level! :debug)  ; Show all logs
  (set-log-level! :info)   ; Hide debug logs
  (set-log-level! :warn)   ; Only warnings and errors
  (set-log-level! :error)  ; Only errors
  (set-log-level! :silent) ; No logs

  ;; Check current log level
  (get-log-level) ; => :debug

  ;; Logging examples
  (debug "my.namespace" "Starting initialization" {:config "loaded"})
  (info "my.namespace" "Server started on port" 3000)
  (warn "my.namespace" "Deprecated function called" 'old-fn)
  (error "my.namespace" "Failed to connect" {:error "timeout"})

  ;; Event tracking (ClojureScript only, no-op on Clojure)
  (track "page-view" {:page "/dashboard"})
  (track "button-click" {:button "save" :context "form"})

  ;; =============================================================================
  ;; ClojureScript Configuration (highest priority)
  ;;
  ;; Set log level via HTML data attribute:
  ;; <div id="root" data-log-level="info">
  ;;
  ;; Build-time defaults:
  ;; - Development builds: :debug level (show everything)
  ;; - Production builds: :warn level (warnings and errors only)

  ;; =============================================================================
  ;; Clojure Configuration (via lambdaisland/config)
  ;;
  ;; Add to resources/parts/config.edn:
  ;; {:log-level :debug
  ;;  :log-output-modes #{:simple :mulog}}
  ;;
  ;; :log-level
  ;;   Valid values: :debug, :info, :warn, :error, :silent
  ;;   Default: :warn
  ;;
  ;; :log-output-modes
  ;;   A set containing one or both of:
  ;;   - :simple  - Plain text output to stdout (good for debugging)
  ;;   - :mulog   - Structured events via mulog (good for production)
  ;;   Default: #{:simple}
  ;;
  ;; Examples:
  ;;   #{:simple}        - Only stdout printing
  ;;   #{:mulog}         - Only structured logging
  ;;   #{:simple :mulog} - Both outputs (dual mode)
  ;;
  ;; You can also override via environment:
  ;; PARTS_LOG_LEVEL=debug clojure -M:run/app
  ;; PARTS_LOG_OUTPUT_MODES=:simple,:mulog clojure -M:run/app
  )
