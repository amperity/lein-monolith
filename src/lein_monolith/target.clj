(ns lein-monolith.target
  "Functions for constructing and operating on dependency closures."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    (lein-monolith
      [config :as config]
      [dependency :as dep])))


(def selection-opts
  {:in* 1
   :upstream 0
   :upstream-of* 1
   :downstream 0
   :downstream-of* 1
   :skip* 1
   :select* 1})


(defn- resolve-projects
  "Returns a set of project names that have been resolved from the given
  sequence, which may consist of multiple comma-separated lists. Ignores names
  which do not map to a project."
  [subprojects name-args]
  (some->>
    (seq name-args)
    (mapcat #(str/split % #"\s*,\s*"))
    (map read-string)
    (keep (partial dep/resolve-name (keys subprojects)))
    (set)))


(defn- resolve-selectors
  "Returns a selection function to filter projects based on the `:select`
  options passed. Multiple selection functions apply cumulative layers of
  filtering, meaning a project must pass _every_ selector to be included.
  Returns nil if no selectors were specified."
  [monolith select-args]
  (some->>
    (seq select-args)
    (map read-string)
    (map (partial config/get-selector monolith))
    (apply every-pred)))


(defn- filter-selected
  "Applies a project-selector function to the topologically-sorted set of
  projects to produce a final sequence of projects."
  [subprojects selector targets]
  (->>
    targets
    (map-indexed (fn [i p] [p (assoc (subprojects p) :monolith/index i)]))
    (filter (comp selector second))
    (map first)))


(defn select
  "Returns a set of canonical project names selected by the given options."
  [monolith subprojects project-name opts]
  (let [dependencies (dep/dependency-map subprojects)
        upstream-of (cond-> (resolve-projects subprojects (:upstream-of opts))
                      (:upstream opts) (conj project-name))
        downstream-of (cond-> (resolve-projects subprojects (:downstream-of opts))
                        (:downstream opts) (conj project-name))
        skippable (resolve-projects subprojects (:skip opts))
        selector (resolve-selectors monolith (:select opts))]
    (->
      ; Start with explicitly-specified 'in' targets.
      (resolve-projects subprojects (:in opts))
      (as-> targets
        ; Merge all targeted upstream dependencies.
        (reduce set/union targets (map (partial dep/upstream-keys dependencies) upstream-of))
        ; Merge all targeted downstream dependencies.
        (reduce set/union targets (map (partial dep/downstream-keys dependencies) downstream-of))
        ; If target set empty, replace with full set.
        (if (empty? targets) (set (keys subprojects)) targets))
      (cond->>
        ; Exclude all 'skip' targets.
        skippable (remove skippable)
        ; Filter using the selector, if any.
        selector (filter-selected subprojects selector))
      (set))))
