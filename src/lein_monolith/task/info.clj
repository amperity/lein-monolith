(ns lein-monolith.task.info
  (:require
    [clojure.string :as str]
    [lein-monolith.color :refer [colorize]]
    [lein-monolith.config :as config]
    [lein-monolith.dependency :as dep]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.target :as target]
    [lein-monolith.task.util :as u]
    [leiningen.core.main :as lein]))


(defn- inherited-tags
  "Builds tags for printing with the inherited properties, e.g. `(leaky, raw)`."
  [{:keys [leaky raw]}]
  (some->> (cond-> []
             leaky (conj "leaky")
             raw (conj "raw"))
           seq
           (str/join ", ")
           (format " (%s)")))


(defn- print-inherited-info
  "Show information about the inherited profiles present within the monorepo
  configuration."
  [monolith]
  (doseq [[_profile-key {:keys [info ks]}] plugin/profile-config]
    (when-let [inherited (get-in monolith [:monolith (:inherit ks)])]
      (println (str "Inherited properties" (inherited-tags info) ":"))
      (doseq [kw inherited] (println (colorize [:bold :yellow] kw)))
      (newline))))


(defn info
  "Show information about the monorepo configuration."
  [project opts]
  (let [monolith (config/find-monolith! project)]
    (when-not (:bare opts)
      (println "Monolith root:" (:root monolith))
      (newline)
      (print-inherited-info monolith)
      (when-let [dirs (get-in monolith [:monolith :project-dirs])]
        (println "Subproject directories:")
        (doseq [dir dirs]
          (println (colorize :magenta dir)))
        (newline)))
    (let [subprojects (config/read-subprojects! monolith)
          dependencies (dep/dependency-map subprojects)
          targets (target/select monolith subprojects opts)
          prefix-len (inc (count (:root monolith)))]
      ; IDEA: some kind of stats about dependency graph shape
      (when-not (:bare opts)
        (printf "Internal projects (%d):\n" (count targets)))
      (doseq [subproject-name (dep/topological-sort dependencies targets)
              :let [{:keys [version root]} (get subprojects subproject-name)
                    relative-path (subs (str root) prefix-len)]]
        (if (:bare opts)
          (println subproject-name relative-path)
          (printf "  %-90s   %s\n"
                  (str (colorize :red \[)
                       subproject-name
                       \space
                       (colorize :magenta (pr-str version))
                       (colorize :red \]))
                  (colorize :cyan relative-path)))))))


(defn lint
  "Check various aspects of the monolith and warn about possible problems."
  [project opts]
  (let [[_ subprojects] (u/load-monolith! project)]
    (when (:deps opts)
      (doseq [[dep-name coords] (->> (vals subprojects)
                                     (mapcat dep/sourced-dependencies)
                                     (group-by first))]
        (dep/select-dependency dep-name coords)))))


(defn deps
  "Print a list of subprojects and the (internal) projects they depend on.
  Targeting options may be used to scope down the projects listed."
  [project opts]
  (let [[monolith subprojects] (u/load-monolith! project)
        targets (target/select monolith subprojects opts)
        dependencies (dep/dependency-map subprojects)]
    (doseq [project-name targets]
      (doseq [dependency (get dependencies project-name)]
        (when (if (contains? subprojects dependency)
                (u/parse-bool (:internal opts "true"))
                (u/parse-bool (:external opts "false")))
          (if (:bare opts)
            (printf "%s\t%s\n" project-name dependency)
            (println (colorize :bold project-name) "->" dependency)))))))


(defn deps-on
  "Print a list of subprojects which depend on the given package(s). Defaults
  to the current project if none are provided."
  [project opts project-names]
  (let [[_ subprojects] (u/load-monolith! project)
        dep-map (dep/dependency-map subprojects)
        resolved-names (map (partial dep/resolve-name! (keys dep-map))
                            project-names)]
    (doseq [dep-name resolved-names]
      (when-not (:bare opts)
        (lein/info "\nSubprojects which depend on" (colorize [:bold :yellow] dep-name)))
      (doseq [subproject-name (dep/topological-sort dep-map)]
        (when-let [spec (->> (get-in subprojects [subproject-name :dependencies])
                             (filter (comp #{dep-name} dep/condense-name first))
                             (first))]
          (if (:bare opts)
            (println subproject-name (first spec) (second spec))
            (println "  " (colorize :bold subproject-name)
                     "->" (colorize :bold spec))))))))


(defn deps-of
  "Print a list of subprojects which given package(s) depend on. Defaults to
  the current project if none are provided."
  [project opts project-names]
  (let [[_ subprojects] (u/load-monolith! project)
        dep-map (dep/dependency-map subprojects)
        resolved-names (map (partial dep/resolve-name! (keys dep-map))
                            project-names)]
    (doseq [project-name resolved-names]
      (when-not (get dep-map project-name)
        (lein/abort project-name "is not a valid subproject!"))
      (when-not (:bare opts)
        (lein/info "\nSubprojects which" (colorize [:bold :yellow] project-name)
                   (if (:transitive opts)
                     "transitively depends on"
                     "depends on")))
      (doseq [dep (if (:transitive opts)
                    (-> (dep/upstream-keys dep-map project-name)
                        (disj project-name)
                        (->> (dep/topological-sort dep-map)))
                    (dep-map project-name))]
        (if (:bare opts)
          (println project-name dep)
          (println "  " (colorize :bold project-name)
                   "->" dep))))))
