(ns aps.parts.architecture-test
  "Architecture-fitness tests: enforce invariants about where certain patterns
   may appear in the codebase. Catches accidental leaks of temporal SQL or
   hard-DELETE statements into namespaces that shouldn't own those concerns."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private src-root "src/main/aps/parts")

(defn- clj-files [root]
  (->> (io/file root)
       file-seq
       (filter #(.isFile %))
       (filter (fn [f]
                 (let [n (.getName f)]
                   (or (str/ends-with? n ".clj")
                       (str/ends-with? n ".cljc")))))))

(defn- relative-path [f]
  (.getPath f))

(defn- namespaces-allowed [allow-substrings f]
  (let [path (relative-path f)]
    (some #(str/includes? path %) allow-substrings)))

(defn- offending-files
  "Return [path] pairs for files that contain any of `patterns` and aren't
   in the allowed-list."
  [patterns allow-substrings]
  (->> (clj-files src-root)
       (remove #(namespaces-allowed allow-substrings %))
       (filter (fn [f]
                 (let [content (slurp f)]
                   (some #(re-find % content) patterns))))
       (map relative-path)))

(deftest temporal-vocabulary-is-quarantined
  (testing
   "temporal SQL identifiers appear only in db/bitemporal and db/range-types"
    (let [ ;; Match the identifiers as keywords, PG types, or bare SQL words
          patterns  [#"(?i)\btstzrange\b"
                     #":sys_period\b"
                     #":valid_at\b"]
          allowed   ["db/bitemporal" "db/range_types"]
          offenders (offending-files patterns allowed)]
      (is (empty? offenders)
          (str "These files reference temporal vocabulary but aren't in the "
               "allow-list (db/bitemporal, db/range_types):\n  "
               (str/join "\n  " offenders))))))

(deftest hard-delete-is-quarantined
  (testing "DELETE FROM on temporal tables appears only in db/erasure"
    (let [patterns  [#"(?i)DELETE FROM (parts|relationships|systems|system_metadata)\b"]
          allowed   ["db/erasure"]
          offenders (offending-files patterns allowed)]
      (is (empty? offenders)
          (str "These files issue raw DELETEs against temporal tables but "
               "aren't in db/erasure:\n  "
               (str/join "\n  " offenders))))))
