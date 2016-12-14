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
   :upstream-of* 1
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


(defn- resolve-selector
  "Looks up the subproject selector from the configuration map. Aborts if a
  selector is specified but does not exist."
  [monolith selector-key]
  (when selector-key
    (let [selectors (get-in monolith [:monolith :project-selectors])
          selector (get selectors selector-key)]
      (when-not selector
        (lein/abort (format "Project selector %s is not configured in %s"
                            selector-key (keys selectors))))
      (eval selector))))


(defn- combine-selectors
  "Returns a selection function to filter projects based on the `:select`
  options passed. Multiple selection functions apply cumulative layers of
  filtering, meaning a project must pass _every_ selector to be included.
  Returns nil if no selectors were specified."
  [monolith select-args]
  (some->>
    (seq select-args)
    (map read-string)
    (keep (partial resolve-selector monolith))
    (apply every-pred)))


(defn- filter-selected
  "Applies a project-selector function to the topologically-sorted set of
  projects to produce a final sequence of projects."
  [subprojects selector targets]
  (->>
    (sort targets)
    (map-indexed (fn [i p] [p (assoc (subprojects p) :monolith/index i)]))
    (filter (comp selector second))
    (map first)))


(defn select
  "Returns a set of canonical project names selected by the given options."
  [monolith subprojects opts]
  (let [dependencies (dep/dependency-map subprojects)
        skippable (resolve-projects subprojects (:skip opts))
        selector (combine-selectors monolith (:select opts))]
    (->
      ; Start with explicitly-specified 'in' targets.
      (resolve-projects subprojects (:in opts))
      (as-> targets
        ; Merge all targeted upstream dependencies.
        (->> (:upstream-of opts)
             (resolve-projects subprojects)
             (map (partial dep/upstream-keys dependencies))
             (reduce set/union targets))
        ; Merge all targeted downstream dependencies.
        (->> (:downstream-of opts)
             (resolve-projects subprojects)
             (map (partial dep/downstream-keys dependencies))
             (reduce set/union targets))
        ; If target set empty, replace with full set.
        (if (empty? targets)
          (set (keys subprojects))
          targets))
      (cond->>
        ; Exclude all 'skip' targets.
        skippable (remove skippable)
        ; Filter using the selector, if any.
        selector (filter-selected subprojects selector))
      (set))))
