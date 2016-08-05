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


(defn- opts->args
  "Converts a set of options back into the arguments that created them. Returns
  a sequence of keywords and strings."
  [opts]
  (concat
    (when (:subtree opts)
      [:subtree])
    (when-let [selector (ffirst (:select opts))]
      [:select selector])
    (when-let [skips (seq (map first (:skip opts)))]
      (mapcat (partial vector :skip) skips))
    (when-let [start (ffirst (:start opts))]
      [:start start])))


(defn- select-projects
  "Returns a vector of pairs of index numbers and symbols naming the selected
  subprojects."
  [monolith subprojects project-name opts]
  (let [selector (some->> (:select opts) ffirst read-string
                          (config/get-selector monolith))
        skippable (some->> (:skip opts) (map (comp read-string first)) set)
        start-from (some-> (:start opts) ffirst read-string)]
    (->
      (dep/dependency-map subprojects)
      (cond->
        (:subtree opts)
          (dep/subtree-from project-name))
      (dep/topological-sort)
      (cond->>
        skippable
          (remove skippable)
         selector
          (filter (comp selector subprojects)))
      (->>
        (map-indexed vector))
      (cond->>
        start-from
          (drop-while (comp (partial not= start-from) second))))))


(defn- apply-subproject-task
  "Applies the task to the given subproject."
  [monolith subproject task]
  (binding [lein/*exit-process?* false]
    (as-> subproject subproject
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
      (lein/apply-task (first task) subproject (rest task)))))


(defn run-tasks
  "Iterate over each subproject in the monolith and apply the given task."
  [project opts task]
  (let [[monolith subprojects] (u/load-monolith! project)
        targets (select-projects monolith subprojects (dep/project-name project) opts)
        n (inc (first (last targets)))
        start-time (System/nanoTime)]
    (when (empty? targets)
      (lein/abort "Iteration selection matched zero subprojects!"))
    (lein/info "Applying"
               (ansi/sgr (str/join " " task) :bold :cyan)
               "to" (ansi/sgr (count targets) :cyan)
               "subprojects...")
    (doseq [[i subproject-name] targets]
      ; Try to reclaim some memory before running the task.
      (System/gc)
      (try
        (lein/info (format "\nApplying to %s (%s/%s)"
                           (ansi/sgr subproject-name :bold :yellow)
                           (ansi/sgr (inc i) :cyan)
                           (ansi/sgr n :cyan)))
        (apply-subproject-task monolith (get subprojects subproject-name) task)
        (catch Exception ex
          (let [resume-args (concat
                              ["lein monolith each"]
                              (opts->args (dissoc opts :start))
                              [:start subproject-name]
                              task)]
            (lein/warn (format "\n%s %s\n"
                               (ansi/sgr "Resume with:" :bold :red)
                               (str/join " " resume-args))))
          (throw ex))))
    (lein/info (format "\n%s: Applied %s to %s projects in %.3f seconds"
                       (ansi/sgr "SUCCESS" :bold :green)
                       (ansi/sgr (str/join " " task) :bold :cyan)
                       (ansi/sgr (count targets) :cyan)
                       (/ (- (System/nanoTime) start-time) 1000000000.0M)))))
