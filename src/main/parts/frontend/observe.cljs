(ns parts.frontend.observe)

(def ^:private log-levels
  {:debug 0
   :info 1
   :warn 2
   :error 3
   :silent 4})

(defn- get-initial-log-level
  "Determine initial log level from data attribute or build environment"
  []
  (let [root-el (when (exists? js/document)
                  (js/document.getElementById "root"))
        data-level (when root-el (.getAttribute root-el "data-log-level"))
        explicit-level (when data-level (keyword data-level))]
    (cond
      (and explicit-level (contains? log-levels explicit-level)) explicit-level
      goog.DEBUG :debug
      :else :warn)))

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

(def ^:private level-colors
  {:debug "color: #6b7280; font-weight: normal;"  ; gray
   :info "color: #2563eb; font-weight: bold;"     ; blue  
   :warn "color: #d97706; font-weight: bold;"     ; orange
   :error "color: #dc2626; font-weight: bold;"})  ; red

(defn- format-message
  [namespace level & args]
  (let [level-style (level-colors level)
        prefix (str "%c[" (name level) "]%c [" namespace "]")]
    (concat [prefix level-style ""] args)))

(defn debug
  "Log debug message with namespace context"
  [namespace & args]
  (when (should-log? :debug)
    (apply js/console.log (format-message namespace :debug args))))

(defn info
  "Log info message with namespace context"
  [namespace & args]
  (when (should-log? :info)
    (apply js/console.info (format-message namespace :info args))))

(defn warn
  "Log warning message with namespace context"
  [namespace & args]
  (when (should-log? :warn)
    (apply js/console.warn (format-message namespace :warn args))))

(defn error
  "Log error message with namespace context"
  [namespace & args]
  (when (should-log? :error)
    (apply js/console.error (format-message namespace :error args))))

(defn track
  "track an event NAME in Plausible, with PROPS"
  [name props]
  (when (js/window.plausible)
    (js/window.plausible name
                         (clj->js {:props props}))))

(comment
  ;; Runtime log level control
  (set-log-level! :debug)  ; Show all logs
  (set-log-level! :info)   ; Hide debug logs
  (set-log-level! :warn)   ; Only warnings and errors
  (set-log-level! :error)  ; Only errors
  (set-log-level! :silent) ; No logs

  ;; Check current log level
  (get-log-level) ; => :debug

  ;; HTML configuration (highest priority)
  ;; <div id="root" data-log-level="info">

  ;; Build-time defaults:
  ;; - Development builds: :debug level (show everything)
  ;; - Production builds: :warn level (warnings and errors only)
  )
