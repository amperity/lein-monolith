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
    [manifold.deferred :as d]
    [puget.color.ansi :as ansi]))


(def task-opts
  {:endure 0
   :subtree 0
   :parallel 0
   :select 1
   :skip 1
   :start 1})


(defn- opts->args
  "Converts a set of options back into the arguments that created them. Returns
  a sequence of keywords and strings."
  [opts]
  (concat
    (when (:endure opts)
      [:endure])
    (when (:subtree opts)
      [:subtree])
    (when (:parallel opts)
      [:parallel])
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
      ; Convert subproject map into {project-sym [dep-syms]} map
      (dep/dependency-map subprojects)
      (cond->
        ; If subtree is set, prune dependency map down to transitive dep
        ; closure of the current project.
        (:subtree opts) (dep/subtree-from project-name))
      ; Sort project names by dependency order into [project-sym ...]
      (dep/topological-sort)
      (cond->>
        ; Remove the set of project names to skip, if any.
        skippable (remove skippable))
      (cond->
        ; If selector is present, filter candidates by providing the project
        ; map with an extra index key for selection logic.
        selector (->> (map-indexed (fn [i p] [p (assoc (subprojects p) :monolith/index i)]))
                      (filter (comp selector second))
                      (map first)))
      (->>
        ; Pair each selected project name up with an index [[i project-sym] ...]
        (map-indexed vector))
      (cond->>
        ; Skip projects until the starting project, if provided.
        start-from (drop-while (comp (partial not= start-from) second))))))


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
      (config/debug-profile "apply-task"
        (lein/apply-task (first task) subproject (rest task))))))


(defn- run-task!
  "Runs the given task, returning a map of information about the run."
  [monolith subproject subproject-name i n task opts]
  ; Try to reclaim some memory before running the task.
  (System/gc)
  (let [start (System/nanoTime)]
    (try
      (lein/info (format "\nApplying to %s (%s/%s)"
                         (ansi/sgr subproject-name :bold :yellow)
                         (ansi/sgr (inc i) :cyan)
                         (ansi/sgr n :cyan)))
      (apply-subproject-task monolith subproject task)
      {:success true
       :elapsed (/ (- (System/nanoTime) start) 1000000.0)}
      (catch Exception ex
        (when-not (:parallel opts)
          (let [resume-args (concat
                              ["lein monolith each"]
                              (opts->args (dissoc opts :start))
                              [:start subproject-name]
                              task)]
            (lein/warn (format "\n%s %s\n"
                               (ansi/sgr "Resume with:" :bold :red)
                               (str/join " " resume-args)))))
        (when-not (:endure opts)
          (throw ex))
        {:success false
         :elapsed (/ (- (System/nanoTime) start) 1000000.0)
         :error ex}))))


(defn- run-linear!
  "Runs the task for each target in a linear (single-threaded) fashion."
  [monolith subprojects n targets task opts]
  (reduce
    (fn [results [i subproject-name]]
      (let [result (run-task! monolith
                              (get subprojects subproject-name)
                              i n task opts)]
        (assoc results subproject-name (assoc result :index i))))
    {} targets))


(defn- run-parallel!
  [monolith subprojects n targets task opts]
  (let [deps (dep/dependency-map subprojects)
        computations (reduce
                       (fn [computations [i target]]
                         (let [subproject (get subprojects target)
                               dependencies (->> (deps target)
                                                 (map (juxt identity computations))
                                                 (remove (comp nil? second))
                                                 (into {}))]
                           (assoc computations
                                  target
                                  (d/chain
                                    (apply d/zip (vals dependencies))
                                    (fn [dependency-results]
                                      (d/future
                                        (run-task! monolith
                                                   subproject
                                                   target
                                                   i n
                                                   task
                                                   opts)))))))
                       {} targets)]
    (doseq [[subproject-name computation] computations]
      (println "Waiting on" subproject-name)
      @computation)))


(defn run-tasks
  "Iterate over each subproject in the monolith and apply the given task."
  [project opts task]
  (let [[monolith subprojects] (u/load-monolith! project)
        targets (select-projects monolith subprojects (dep/project-name project) opts)
        n (inc (or (first (last targets)) -1))
        start-time (System/nanoTime)]
    (when (empty? targets)
      (lein/abort "Iteration selection matched zero subprojects!"))
    (lein/info "Applying"
               (ansi/sgr (str/join " " task) :bold :cyan)
               "to" (ansi/sgr (count targets) :cyan)
               "subprojects...")
    (let [results (if (:parallel opts)
                    (run-parallel! monolith subprojects n targets task opts)
                    (run-linear! monolith subprojects n targets task opts))]
      (if-let [failures (seq (map key (remove (comp :success val) results)))]
        (lein/abort (format "\n%s: Applied %s to %s projects in %.3f seconds with %d failures: %s"
                            (ansi/sgr "FAILURE" :bold :red)
                            (ansi/sgr (str/join " " task) :bold :cyan)
                            (ansi/sgr (count targets) :cyan)
                            (/ (- (System/nanoTime) start-time) 1000000000.0M)
                            (count failures)
                            (str/join " " failures)))
        (lein/info (format "\n%s: Applied %s to %s projects in %.3f seconds"
                           (ansi/sgr "SUCCESS" :bold :green)
                           (ansi/sgr (str/join " " task) :bold :cyan)
                           (ansi/sgr (count targets) :cyan)
                           (/ (- (System/nanoTime) start-time) 1000000000.0M)))))))
