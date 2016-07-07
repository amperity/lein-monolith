(ns lein-monolith.util
  "Various utility functions."
  (:require
    [clojure.set :as set]))


;; ## General Utilities

(defn map-vals
  "Helper function to map over the values in a map and re-use the keys."
  [f m]
  (zipmap (keys m) (map f (vals m))))


(defn parse-kw-args
  "Given a sequence of string arguments, parse out expected keywords. Returns
  a vector with a map of keywords to values (or `true` for flags) followed by
  a sequence the remaining unparsed arguments."
  [expected args]
  (loop [opts {}
         args args]
    (let [kw (and (first args)
                  (.startsWith ^String (first args) ":")
                  (keyword (subs (first args) 1)))
          arg-count (get expected kw)]
      (cond
        ; Not an expected kw arg
        (nil? arg-count)
          [opts args]

        ; Flag keyword
        (zero? arg-count)
          (recur (assoc opts kw true) (rest args))

        ; Multi-arg keyword
        :else
          (recur
            (update opts kw (fnil conj []) (vec (take arg-count (rest args))))
            (drop (inc arg-count) args))))))



;; ## Dependency Resolution

(defn subtree-from
  "Takes a map of node keys to sets of dependent nodes and a root node to start
  from. Returns the same dependency map containing only keys in the transitive
  subtree of the root."
  [m root]
  (loop [result {}
         front [root]]
    (if-let [node (first front)]
      (if (contains? m node)
        ; Node is part of the internal tree.
        (let [deps (set (get m node))
              new-front (set/difference deps (set (keys result)))]
          (recur
            ; Add the node to the result map.
            (assoc result node deps)
            ; Add any unprocessed dependencies to the front.
            (concat (next front) (set/difference deps (set (keys result))))))
        ; Node is not internal, so ignore.
        (recur result (next front)))
      ; No more nodes to process.
      result)))


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



;; ## Coordinate Functions

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


(defn unscope-coord
  "Removes the `:scope` entry from a leiningen dependency coordinate vector,
  if it is present. Preserves any metadata on the coordinate."
  [coord]
  (-> coord
      (->> (partition-all 2)
           (mapcat #(when-not (= :scope (first %)) %)))
      (vec)
      (with-meta (meta coord))))


(defn with-source
  "Attaches metadata to a dependency vector which notes the source project."
  [dependency project-name]
  (vary-meta dependency assoc :monolith/project project-name))


(defn dep-source
  "Retrieves the project which pulled in the dependency from metadata on the
  spec vector."
  [dependency]
  (:monolith/project (meta dependency)))
