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
    [manifold.executor :as executor]
    [puget.color.ansi :as ansi]))


(def task-opts
  {:endure 0
   :subtree 0
   :parallel 1
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
    (when-let [threads (ffirst (:parallel opts))]
      [:parallel threads])
    (when (:report opts)
      [:report])
    (when-let [selector (ffirst (:select opts))]
      [:select selector])
    (when-let [skips (seq (map first (:skip opts)))]
      (mapcat (partial vector :skip) skips))
    (when-let [start (ffirst (:start opts))]
      [:start start])))


(defn- print-report
  "Reports information about the tasks given a results map."
  [results elapsed]
  (let [task-time (reduce + (keep :elapsed results))
        speedup (/ task-time elapsed)]
    (lein/info (format "\n%s  %11s"
                       (ansi/sgr "Run time:" :bold :cyan)
                       (u/human-duration elapsed)))
    (lein/info (format "%s %11s"
                       (ansi/sgr "Task time:" :bold :cyan)
                       (u/human-duration task-time)))
    (lein/info (format "%s   %11.1f"
                       (ansi/sgr "Speedup:" :bold :cyan)
                       speedup))
    (lein/info (->> results
                    (sort-by :elapsed)
                    (reverse)
                    (take 8)
                    (map #(format "%-45s %s %11s"
                                  (ansi/sgr (:name %) :bold :yellow)
                                  (if (:success %) " " "!")
                                  (u/human-duration (:elapsed %))))
                    (str/join "\n")
                    (str \newline
                         (ansi/sgr "Slowest projects:" :bold :cyan)
                         \newline)))))


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
  [ctx target]
  ; Try to reclaim some memory before running the task.
  (System/gc)
  (let [start (System/nanoTime)
        opts (:opts ctx)
        subproject (get-in ctx [:subprojects target])
        results (delay {:name target
                        :elapsed (/ (- (System/nanoTime) start) 1000000.0)})]
    (try
      (lein/info (format "\nApplying to %s" (ansi/sgr target :bold :yellow)))
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
        (assoc @results :success false, :error ex))
      (finally
        (lein/info (format "Completed %s (%s/%s) in %s"
                           (ansi/sgr target :bold :yellow)
                           (ansi/sgr (swap! (:completions ctx) inc) :cyan)
                           (ansi/sgr (:num-targets ctx) :cyan)
                           (ansi/sgr (u/human-duration (:elapsed @results)) :bold :cyan)))))))


(defn- run-linear!
  "Runs the task for each target in a linear (single-threaded) fashion. Returns
  a vector of result maps in the order the tasks were executed."
  [ctx targets]
  (mapv (comp (partial apply run-task! ctx) second) targets))


(defn- run-parallel!
  "Runs the tasks for targets in multiple worker threads, chained by dependency
  order. Returns a vector of result maps in the order the tasks finished executing."
  [ctx threads targets]
  (let [task-name (first (:task ctx))
        deps (dep/dependency-map (:subprojects ctx))
        thread-pool (executor/fixed-thread-executor threads)]
    ; Perform an initial resolution of the task to prevent metadata-related
    ; arglist errors when namespaces are loaded in parallel.
    (lein/resolve-task (first (:task ctx)))
    (->
      (reduce
        (fn future-builder
          [computations [i target]]
          (let [dependencies (keep computations (deps target))
                task-runner (fn task-runner
                              [dependency-results]
                              (d/future-with thread-pool
                                (lein/debug "Starting project" target)
                                (run-task! ctx target)))
                task-future (if (seq dependencies)
                              (d/chain (apply d/zip dependencies) task-runner)
                              (task-runner nil))]
            (assoc computations target task-future)))
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
    (when (and (:start opts) (:parallel opts))
      (lein/abort "The :parallel and :start options are not compatible"))
    (when (empty? targets)
      (lein/abort "Iteration selection matched zero subprojects!"))
    (lein/info "Applying"
               (ansi/sgr (str/join " " task) :bold :cyan)
               "to" (ansi/sgr (count targets) :cyan)
               "subprojects...")
    (let [ctx {:monolith monolith
               :subprojects subprojects
               :completions (atom (ffirst targets))
               :num-targets n
               :task task
               :opts opts}
          results (if-let [threads (read-string (ffirst (:parallel opts)))]
                    (run-parallel! ctx threads targets)
                    (run-linear! ctx targets))
          elapsed (/ (- (System/nanoTime) start-time) 1000000.0)]
      (when (:report opts)
        (print-report results elapsed))
      (if-let [failures (seq (map :name (remove :success results)))]
        (lein/abort (format "\n%s: Applied %s to %s projects in %s with %d failures: %s"
                            (ansi/sgr "FAILURE" :bold :red)
                            (ansi/sgr (str/join " " task) :bold :cyan)
                            (ansi/sgr (count targets) :cyan)
                            (u/human-duration elapsed)
                            (count failures)
                            (str/join " " failures)))
        (lein/info (format "\n%s: Applied %s to %s projects in %s"
                           (ansi/sgr "SUCCESS" :bold :green)
                           (ansi/sgr (str/join " " task) :bold :cyan)
                           (ansi/sgr (count targets) :cyan)
                           (u/human-duration elapsed)))))))
