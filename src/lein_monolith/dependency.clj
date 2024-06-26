(ns lein-monolith.dependency
  "Functions for working with dependency coordinates and graphs."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [lein-monolith.color :refer [colorize]]
    [leiningen.core.main :as lein]))


;; ## Coordinate Functions

(defn condense-name
  "Simplifies a dependency name symbol with identical name and namespace
  components to a symbol with just a name."
  [sym]
  (when sym
    (if (= (namespace sym) (name sym))
      (symbol (name sym))
      sym)))


(defn project-name
  "Extracts the (condensed) project name from a project definition map."
  [project]
  (when project
    (condense-name (symbol (:group project) (:name project)))))


(defn resolve-name
  "Given a set of valid project names, determine the match for the named
  project. This can be used to resolve the short name (meaning, no namespace)
  to a fully-qualified project name. Returns a resolved key from
  `project-names`, a collection of multiple matching keys, or nil if the
  resolution fails."
  [project-names sym]
  (let [valid-keys (set project-names)]
    (cond
      (valid-keys sym)
      sym

      (valid-keys (condense-name sym))
      (condense-name sym)

      (nil? (namespace sym))
      (let [candidates (filter #(= (name %) (name sym)) valid-keys)]
        (if (= 1 (count candidates))
          (first candidates)
          (seq candidates)))

      :else nil)))


(defn resolve-name!
  "Resolves a symbol to a single project name, or calls abort if no or multiple
  projects match."
  [project-names sym]
  (let [result (resolve-name project-names sym)]
    (cond
      (nil? result)
      (lein/abort "Could not resolve" sym "to any monolith subproject!")

      (coll? result)
      (lein/abort "Name" sym "resolves to multiple monolith subprojects:"
                  (str/join " " (sort result)))

      :else result)))


(defn clean-coord
  "Removes the `:scope` entry from a leiningen dependency coordinate vector,
  if it is present. Preserves any metadata on the coordinate."
  [coord]
  (into (empty coord)
        (comp
          (partition-all 2)
          (remove (comp #{:scope :exclusions} first))
          cat)
        coord))


(defn with-source
  "Attaches metadata to a dependency vector which notes the source project."
  [dependency project-name]
  (vary-meta dependency assoc :monolith/project project-name))


(defn dep-source
  "Retrieves the project which pulled in the dependency from metadata on the
  spec vector."
  [dependency]
  (:monolith/project (meta dependency)))


;; ## Dependency Graphs

(defn- collect-dependencies
  "Merges the project's top-level dependencies with all dependencies listed in
  the project's profiles to ensure the project has the proper dependency closure
  for compilation ordering."
  [project]
  (->>
    project
    (:profiles)
    (vals)
    (cons project)
    (mapcat :dependencies)
    (map (comp condense-name first))
    (set)))


(defn dependency-map
  "Converts a map of project names to definitions into a map of project names
  to sets of projects that node depends on."
  [projects]
  (->>
    (vals projects)
    (map collect-dependencies)
    (zipmap (keys projects))))


(defn upstream-keys
  "Returns a set of the keys which are upstream of a given node in the
  dependency map. Includes the root value itself."
  [dependencies root]
  (loop [result #{}
         queue (conj (clojure.lang.PersistentQueue/EMPTY) root)]
    (cond
      ;; Nothing left to process.
      (empty? queue) result

      ;; Already seen this node.
      (contains? result (peek queue))
      (recur result (pop queue))

      ;; Add next set of dependencies.
      :else
      (let [node (peek queue)
            deps (dependencies node)]
        (recur (conj result node)
               (into (pop queue) (set/difference deps result)))))))


(defn downstream-keys
  "Returns a set of the keys which are downstream of a given node in the
  dependency map. Includes the root value itself."
  [dependencies root]
  (let [deps-on (fn deps-on
                  [n]
                  (set (keep (fn [[k deps]] (when (deps n) k))
                             dependencies)))]
    (loop [result #{}
           queue (conj (clojure.lang.PersistentQueue/EMPTY) root)]
      (cond
        ;; Nothing left to process.
        (empty? queue) result

        ;; Already seen this node, deps are either present or already queued.
        (contains? result (peek queue))
        (recur result (pop queue))

        ;; Add next set of dependencies.
        :else
        (let [node (peek queue)
              consumers (deps-on node)]
          (recur (conj result node)
                 (into (pop queue) (set/difference consumers result))))))))


(defn unique-cycles
  "Return a set of all unique cycles in dependency graph m."
  [m]
  {:pre [(map? m)]
   :post [(set? %)]}
  (let [path->cycles (fn path->cycles
                       [path]
                       {:pre [(seq path)]}
                       (let [k (peek path)
                             vs (m k)
                             path-set (set path)]
                         (mapcat (fn [v]
                                   (if (path-set v)
                                     ;; found the cycle
                                     [(into []
                                            ;; drop non-cyclic prefix
                                            (drop-while (complement #{v}))
                                            (conj path v))]
                                     (path->cycles
                                       (conj path v))))
                                 vs)))
        all-cycles (mapcat #(path->cycles [%]) (keys m))
        ;; remove duplicate cycles (that involve the same deps) -- like
        ;;   (into #{}
        ;;         (map (comp first val))
        ;;         (group-by set all-cycles))
        ;; but in a single pass.
        [cycles-vecs _] (reduce (fn [[cycles-vecs cycle-sets] c]
                                  (let [cset (set c)]
                                    (if (cycle-sets cset)
                                      [cycles-vecs cycle-sets]
                                      [(conj cycles-vecs c)
                                       (conj cycle-sets cset)])))
                                [#{} #{}]
                                all-cycles)]
    cycles-vecs))


(defn pretty-cycle
  "Returns a pretty-printed string representation of cycle c.

  eg. (println (pretty-cycle [1 2 3 1])) =>
      + 1
      ^ + 2
      |  + 3
      |_/"
  [c]
  {:pre [(vector? c)
         (= (first c) (peek c))]}
  (if (= 2 (count c))
    (str/join \newline
              [(str "+ " (pr-str (peek c)))
               "^\\"
               "|_|"])
    (str/join (map-indexed (fn [indent el]
                             (if (= indent (dec (count c)))
                               ;; draw:
                               ;; |___/
                               (str
                                 \|
                                 (str/join (repeat (max 1 (- indent 2)) \_))
                                 \/)
                               (str
                                 (case (int indent)
                                   0 ""
                                   1 \^
                                   \|)
                                 (str/join (repeat indent \space))
                                 "+ " (pr-str el) "\n")))
                           c))))


(defn topological-sort
  "Returns a sequence of the keys in the map `m`, ordered such that no key `k1`
  appearing before `k2` satisfies `(contains? (upstream-keys m k1) k2)`. In
  other words, earlier keys do not transitively depend on any later keys."
  ([m]
   (when (seq m)
     ;; Note that 'roots' here are keys which no other keys depend on, hence
     ;; should appear *later* in the sequence.
     (let [roots (apply set/difference (set (keys m)) (map set (vals m)))]
       (when (empty? roots)
         (let [cs (->> m
                       unique-cycles
                       (sort-by count))]
           (assert (seq cs) "Found cycle but failed to reproduce")
           (throw (ex-info (str "Dependency cycle"
                                (when (next cs) "s")
                                " detected!\n\n"
                                (str/join "\n\n" (map pretty-cycle cs)))
                           {:cycles cs}))))
       (concat (topological-sort (apply dissoc m roots))
               (sort roots)))))
  ([m ks]
   (filter (set ks) (topological-sort m))))


;; ## Dependency Resolution

(defn sourced-dependencies
  "Given a project map, returns a sequence of dependency coordinates with
  metadata tracking the source."
  [project]
  (let [pn (project-name project)]
    (map #(with-source % pn) (:dependencies project))))


(defn lint-dependency
  "Given a dependency name and a collection of specs for that dependency, warn
  if there are multiple distinct dep coordinates."
  [dep-name specs]
  (let [specs (mapv clean-coord specs)
        projects-for-specs (reduce (fn [m d]
                                     (update m d (fnil conj []) (dep-source d)))
                                   {}
                                   specs)]
    (when (not= 1 (count (distinct specs)))
      (lein/warn (colorize :red (format "WARN: Multiple dependency specs found for %s in %d projects"
                                        (condense-name dep-name)
                                        (count (distinct (map dep-source specs))))))
      (doseq [[spec projects] projects-for-specs]
        (lein/warn (format "%-50s from %s"
                           (pr-str spec)
                           (str/join " " (sort projects)))))
      (lein/warn ""))))
