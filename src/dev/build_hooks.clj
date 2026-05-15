(ns build-hooks
  "Shadow-cljs build hooks for the parts project.

   Hooks are added to a build's config (per `:dev` or `:release`) under
   `:build-hooks` in `shadow-cljs.edn`. See:
   https://shadow-cljs.github.io/docs/UsersGuide.html#build-hooks")

(defn fail-on-warnings
  "Abort the build if any compiled source emitted warnings.

   Runs at the `:flush` stage (after JS output is written and warnings are
   fully collected). Walks `:shadow.build/build-info`'s `:sources` list and
   throws if any source has a non-empty `:warnings` vector; shadow-cljs
   surfaces the throw as a non-zero exit, failing `make build-frontend`
   (and transitively `make dist` and CI)."
  {:shadow.build/stages #{:flush}}
  [build-state]
  (let [warnings (->> (get-in build-state [:shadow.build/build-info :sources])
                      (mapcat :warnings)
                      (remove nil?))]
    (when (seq warnings)
      (throw (ex-info (str "Frontend build aborted: "
                           (count warnings)
                           " warning(s) present (treated as errors in :release).")
                      {:warnings warnings}))))
  build-state)
