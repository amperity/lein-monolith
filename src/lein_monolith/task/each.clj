(ns lein-monolith.task.each
  (:require
    [clojure.string :as str]
    (leiningen.core
      [main :as lein]
      [project :as project])
    (lein-monolith
      [config :as config]
      [dependency :as dep]
      [plugin :as plugin])
    [lein-monolith.task.util :as u]
    [puget.color.ansi :as ansi]))


(def task-opts
  {:subtree 0
   :select 1
   :skip 1
   :start 1})


(defn run-projects
  "Iterate over each subproject in the monolith and apply the given task."
  [project opts task]
  (let [[monolith subprojects] (u/load-monolith! project)
        selector (some->> (:select opts) ffirst read-string
                          (config/get-selector monolith))
        skippable (some->> (:skip opts) (map (comp read-string first)) set)
        start-from (some-> (:start opts) ffirst read-string)
        candidates (-> (dep/dependency-map subprojects)
                       (cond->
                         (:subtree opts)
                           (dep/subtree-from (dep/project-name project)))
                       (dep/topological-sort)
                       (cond->>
                         skippable
                           (remove skippable)
                         selector
                           (filter (comp selector subprojects))))
        targets (-> (map-indexed vector candidates)
                    (cond->>
                      start-from
                        (drop-while (comp (partial not= start-from) second))))
        start-time (System/nanoTime)]
    (when (empty? targets)
      (lein/abort "Iteration selection matched zero subprojects!"))
    (lein/info "Applying"
               (ansi/sgr (str/join " " task) :bold :cyan)
               "to" (ansi/sgr (count targets) :cyan)
               "subprojects...")
    (doseq [[i subproject-name] targets]
      ; TODO: try garbage collection?
      (try
        (binding [lein/*exit-process?* false]
          (lein/info (format "\nApplying to %s (%s/%s)"
                             (ansi/sgr subproject-name :bold :yellow)
                             (ansi/sgr (inc i) :cyan)
                             (ansi/sgr (count candidates) :cyan)))
          (as-> (get subprojects subproject-name) subproject
            (if-let [inherited (:monolith/inherit subproject)]
              (assoc-in subproject [:profiles :monolith/inherited]
                        (plugin/inherited-profile monolith inherited))
              subproject)
            (config/debug-profile "init-subproject"
              (project/init-project
                subproject
                (if (get-in subproject [:profiles :monolith/inherited])
                  [:default :monolith/inherited]
                  [:default])))
            (lein/apply-task (first task) subproject (rest task))))
        (catch Exception ex
          ; TODO: report number skipped, number succeeded, number remaining?
          ; TODO: need to insert additional opts provided
          (lein/warn (format "\n%s lein monolith each :start %s %s\n"
                             (ansi/sgr "Resume with:" :bold :red)
                             subproject-name (str/join " " task)))
          (throw ex))))
    (lein/info (format "\n%s: Applied %s to %s projects in %.3f seconds"
                       (ansi/sgr "SUCCESS" :bold :green)
                       (ansi/sgr (str/join " " task) :bold :cyan)
                       (ansi/sgr (count targets) :cyan)
                       (/ (- (System/nanoTime) start-time) 1000000000.0M)))))
