(ns lein-monolith.task.info
  (:require
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    (lein-monolith
      [ansi :as ansi]
      [config :as config]
      [dependency :as dep]
      [target :as target])
    [lein-monolith.task.util :as u]
    [puget.printer :as puget]))


(defn info
  "Show information about the monorepo configuration."
  [project opts]
  (let [monolith (config/find-monolith! project)]
    (when-not (:bare opts)
      (println "Monolith root:" (:root monolith))
      (newline)
      (when-let [inherited (get-in monolith [:monolith :inherit])]
        (println "Inherited properties:")
        (puget/cprint inherited)
        (newline))
      (when-let [inherited (get-in monolith [:monolith :inherit-leaky])]
        (println "Inherited (leaky) properties:")
        (puget/cprint inherited)
        (newline))
      (when-let [dirs (get-in monolith [:monolith :project-dirs])]
        (println "Subproject directories:")
        (puget/cprint dirs)
        (newline)))
    (let [subprojects (config/read-subprojects! monolith)
          dependencies (dep/dependency-map subprojects)
          targets (target/select monolith subprojects opts)
          prefix-len (inc (count (:root monolith)))]
      (when-not (:bare opts)
        (printf "Internal projects (%d):\n" (count targets)))
      (doseq [subproject-name (dep/topological-sort dependencies targets)
              :let [{:keys [version root]} (get subprojects subproject-name)
                    relative-path (subs (str root) prefix-len)]]
        (if (:bare opts)
          (println subproject-name relative-path)
          (printf "  %-90s   %s\n"
                  (puget/cprint-str [subproject-name version])
                  (ansi/maybe-sgr relative-path :cyan)))))))


(defn lint
  "Check various aspects of the monolith and warn about possible problems."
  [project opts]
  (let [[monolith subprojects] (u/load-monolith! project)]
    ; TODO: lack of :pedantic? :abort
    (when (:deps opts)
      (doseq [[dep-name coords] (->> (vals subprojects)
                                     (mapcat dep/sourced-dependencies)
                                     (group-by first))]
        (dep/select-dependency dep-name coords)))))


(defn deps-on
  "Print a list of subprojects which depend on the given package(s). Defaults
  to the current project if none are provided."
  [project opts project-names]
  (let [[monolith subprojects] (u/load-monolith! project)
        dep-map (dep/dependency-map subprojects)
        resolved-names (map (partial dep/resolve-name! (keys dep-map))
                            project-names)]
    (doseq [dep-name resolved-names]
      (when-not (:bare opts)
        (lein/info "\nSubprojects which depend on" (ansi/maybe-sgr dep-name :bold :yellow)))
      (doseq [subproject-name (dep/topological-sort dep-map)
              :let [{:keys [version dependencies]} (get subprojects subproject-name)]]
        (when-let [spec (first (filter (comp #{dep-name} dep/condense-name first) dependencies))]
          (if (:bare opts)
            (println subproject-name (first spec) (second spec))
            (println "  " (puget/cprint-str subproject-name)
                     "->" (puget/cprint-str spec))))))))


(defn deps-of
  "Print a list of subprojects which given package(s) depend on. Defaults to
  the current project if none are provided."
  [project opts project-names]
  (let [[monolith subprojects] (u/load-monolith! project)
        dep-map (dep/dependency-map subprojects)
        resolved-names (map (partial dep/resolve-name! (keys dep-map))
                            project-names)]
    (doseq [project-name resolved-names]
      (when-not (get dep-map project-name)
        (lein/abort project-name "is not a valid subproject!"))
      (when-not (:bare opts)
        (lein/info "\nSubprojects which" (ansi/maybe-sgr project-name :bold :yellow)
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
          (println "  " (puget/cprint-str project-name)
                   "->" dep))))))
