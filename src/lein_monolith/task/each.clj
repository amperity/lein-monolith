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
   :report 0
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
    (when (:report opts)
      [:report])
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
        (lein/resolve-and-apply subproject task)))))


(defn- run-task!
  "Runs the given task, returning a map of information about the run."
  [ctx i target]
  ; Try to reclaim some memory before running the task.
  (System/gc)
  (let [start (System/nanoTime)
        opts (:opts ctx)
        subproject (get-in ctx [:subprojects target])
        results (delay {:name target
                        :index i
                        :elapsed (/ (- (System/nanoTime) start) 1000000.0)})]
    (try
      (lein/info (format "\nApplying to %s (%s/%s)"
                         (ansi/sgr target :bold :yellow)
                         (ansi/sgr (inc i) :cyan)
                         (ansi/sgr (:num-targets ctx) :cyan)))
      (apply-subproject-task (:monolith ctx) subproject (:task ctx))
      (assoc @results :success true)
      (catch Exception ex
        (when-not (or (:parallel opts) (:endure opts))
          (let [resume-args (concat
                              ["lein monolith each"]
                              (opts->args (dissoc opts :start))
                              [:start target]
                              (:task ctx))]
            (lein/warn (format "\n%s %s\n"
                               (ansi/sgr "Resume with:" :bold :red)
                               (str/join " " resume-args)))))
        (when-not (:endure opts)
          (throw ex))
        (assoc @results :success false, :error ex)))))


(defn- run-linear!
  "Runs the task for each target in a linear (single-threaded) fashion. Returns
  a vector of result maps in the order the tasks were executed."
  [ctx targets]
  (mapv (partial apply run-task! ctx) targets))


(defn- run-parallel!
  "Runs the tasks for targets in multiple worker threads, chained by dependency
  order. Returns a vector of result maps in the order the tasks finished executing."
  [ctx targets]
  (let [task-name (first (:task ctx))
        deps (dep/dependency-map (:subprojects ctx))]
    ; Perform an initial resolution of the task to prevent metadata-related
    ; arglist errors when namespaces are loaded in parallel.
    (lein/resolve-task (first (:task ctx)))
    (->
      (reduce
        (fn [computations [i target]]
          (let [dependencies (->> (deps target)
                                  (keep computations)
                                  (apply d/zip))]
            (assoc computations
                   target
                   (d/chain
                     dependencies
                     (fn [dependency-results]
                       (d/future
                         (lein/debug "Starting project" target)
                         (run-task! ctx i target)))))))
        {} targets)
      (as-> computations
        (mapv (comp deref computations second) targets)))))


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
    (let [ctx {:monolith monolith
               :subprojects subprojects
               :num-targets n
               :task task
               :opts opts}
          results (if (:parallel opts)
                    (run-parallel! ctx targets)
                    (run-linear! ctx targets))]
      (when (:report opts)
        (require 'puget.printer)
        (newline)
        (puget.printer/cprint results)
        (lein/info (format "Total time: %.3f seconds" (/ (reduce + (keep :elapsed results)) 1000.0))))
      (if-let [failures (seq (map :name (remove :success results)))]
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
