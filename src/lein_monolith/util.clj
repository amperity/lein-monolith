(ns lein-monolith.util
  "Various utility functions."
  (:require
    [clojure.set :as set]))


(defn map-vals
  "Helper function to map over the values in a map and re-use the keys."
  [f m]
  (zipmap (keys m) (map f (vals m))))


(defn topological-sort
  "Returns a sequence of the keys in the map `m`, ordered such that no key `k1`
  appearing before `k2` satisfies `(contains? (get m k1) k2)`. In other words,
  earlier keys do not 'depend on' later keys."
  [m]
  (when (seq m)
    ; Note that 'roots' here are keys which no other keys depend on, hence
    ; should appear *later* in the sequence.
    (let [roots (apply set/difference (set (keys m)) (map set (vals m)))]
      (when (empty? roots)
        (throw (ex-info "Cannot sort the keys in the given map, cycle detected!"
                        {:input m})))
      (concat (topological-sort (apply dissoc m roots))
              (sort roots)))))


(defn condense-name
  "Simplifies a dependency name symbol with identical name and namespace
  components to a symbol with just a name."
  [sym]
  (if (= (namespace sym) (name sym))
    (symbol (name sym))
    sym))


(defn project-name
  "Extracts the (condensed) project name from a project definition map."
  [project]
  (condense-name (symbol (:group project) (:name project))))
