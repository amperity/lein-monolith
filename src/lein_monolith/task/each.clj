(ns lein-monolith.task.each
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    (leiningen.core
      [eval :as eval]
      [main :as lein]
      [project :as project]
      [utils :refer [rebind-io!]])
    (lein-monolith
      [ansi :as ansi]
      [config :as config]
      [dependency :as dep]
      [plugin :as plugin]
      [target :as target])
    [lein-monolith.task.util :as u]
    [manifold.deferred :as d]
    [manifold.executor :as executor])
  (:import
    (com.hypirion.io
      ClosingPipe
      Pipe
      RevivableInputStream)
    (java.io
      OutputStream)))


(def task-opts
  (merge
    target/selection-opts
    {:parallel 1
     :endure 0
     :report 0
     :output 1
     :upstream 0
     :downstream 0
     :start 1}))


(defn- opts->args
  "Converts a set of options back into the arguments that created them. Returns
  a sequence of keywords and strings."
  [opts]
  (concat
    (when-let [threads (:parallel opts)]
      [:parallel threads])
    (when (:endure opts)
      [:endure])
    (when (:report opts)
      [:report])
    (when-let [out-dir (:output opts)]
      [:output out-dir])
    (when-let [in (seq (:in opts))]
      [:in (str/join "," in)])
    (when (:upstream opts)
      [:upstream])
    (when-let [uof (seq (:upstream-of opts))]
      [:upstream-of (str/join "," uof)])
    (when (:downstream opts)
      [:downstream])
    (when-let [dof (seq (:downstream-of opts))]
      [:downstream-of (str/join "," dof)])
    (when-let [selectors (seq (:select opts))]
      (mapcat (partial vector :select) selectors))
    (when-let [skips (seq (:skip opts))]
      [:skip (str/join "," skips)])
    (when-let [start (:start opts)]
      [:start start])))


(defn- print-report
  "Reports information about the tasks given a results map."
  [results elapsed]
  (let [task-time (reduce + (keep :elapsed results))
        speedup (/ task-time elapsed)]
    (lein/info (format "\n%s  %11s"
                       (ansi/maybe-sgr "Run time:" :bold :cyan)
                       (u/human-duration elapsed)))
    (lein/info (format "%s %11s"
                       (ansi/maybe-sgr "Task time:" :bold :cyan)
                       (u/human-duration task-time)))
    (lein/info (format "%s   %11.1f"
                       (ansi/maybe-sgr "Speedup:" :bold :cyan)
                       speedup))
    (lein/info (->> results
                    (sort-by :elapsed)
                    (reverse)
                    (take 8)
                    (map #(format "%-45s %s %11s"
                                  (ansi/maybe-sgr (:name %) :bold :yellow)
                                  (if (:success %) " " "!")
                                  (u/human-duration (:elapsed %))))
                    (str/join "\n")
                    (str \newline
                         (ansi/maybe-sgr "Slowest projects:" :bold :cyan)
                         \newline)))))


(defn- select-projects
  "Returns a vector of pairs of index numbers and symbols naming the selected
  subprojects."
  [monolith subprojects project-name opts]
  (let [dependencies (dep/dependency-map subprojects)
        opts' (cond-> opts
                (:upstream opts)
                  (update :upstream-of conj (str project-name))
                (:downstream opts)
                  (update :downstream-of conj (str project-name)))
        targets (target/select monolith subprojects opts')
        start-from (some->> (:start opts)
                            (read-string)
                            (dep/resolve-name! (keys subprojects)))]
    (->
      ; Sort project names by dependency order.
      (dep/topological-sort dependencies targets)
      ; Skip projects until the starting project, if provided.
      (cond->> start-from (drop-while (partial not= start-from)))
      ; Pair names up with an index [[i project-sym] ...]
      (->> (map-indexed vector)))))


(defn- tee-output-stream
  "Constructs a proxy of an OutputStream that will write a copy of the bytes
  given to both A and B."
  ^OutputStream
  [^OutputStream out-a ^OutputStream out-b]
  (proxy [OutputStream] []

    (write
      ([value]
       (locking out-b
         (if (integer? value)
           (do (.write out-a (int value))
               (.write out-b (int value)))
           (do (.write out-a ^bytes value)
               (.write out-b ^bytes value)))))
      ([^bytes byte-arr off len]
       (locking out-b
         (.write out-a byte-arr off len)
         (.write out-b byte-arr off len))))

    (flush
      []
      (.flush out-a)
      (.flush out-b))

    (close
      []
      ; no-op
      nil)))


(def ^:dynamic *task-file-output* nil)


(defn- run-with-output
  "A version of `leiningen.core.eval/sh` that streams in/out/err, teeing output
  to the given file."
  [& cmd]
  (when eval/*pump-in*
    (rebind-io!))
  (when-not *task-file-output*
    (throw (IllegalStateException.
             (str "Cannot run task without bound *task-file-output*: " (pr-str cmd)))))
  (let [env (@#'eval/overridden-env eval/*env*)
        ^Process proc (.exec (Runtime/getRuntime) (into-array String cmd) env (io/file eval/*dir*))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (.destroy proc))))
    (with-open [out (.getInputStream proc)
                err (.getErrorStream proc)
                in (.getOutputStream proc)]
      (let [pump-out (doto (Pipe. out (tee-output-stream System/out *task-file-output*)) .start)
            pump-err (doto (Pipe. err (tee-output-stream System/err *task-file-output*)) .start)
            pump-in (ClosingPipe. System/in in)]
        (when eval/*pump-in* (.start pump-in))
        (.join pump-out)
        (.join pump-err)
        (let [exit-value (.waitFor proc)]
          (when eval/*pump-in*
            (.kill ^RevivableInputStream System/in)
            (.join pump-in)
            (.resurrect ^RevivableInputStream System/in))
          (.flush ^OutputStream *task-file-output*)
          exit-value)))))


(defn- apply-subproject-task
  "Applies the task to the given subproject."
  [monolith subproject task]
  (binding [lein/*exit-process?* false]
    (let [inherited (plugin/build-inherited-profiles monolith subproject)]
      (as-> subproject subproject
        (reduce-kv
          (fn inject-profile [p k v] (assoc-in p [:profiles k] v))
          subproject inherited)
        (config/debug-profile "init-subproject"
          (project/init-project subproject (cons :default (keys inherited))))
        (config/debug-profile "apply-task"
          (binding [eval/*dir* (:root subproject)]
            (lein/resolve-and-apply subproject task)))))))


(defn- apply-subproject-task-with-output
  "Applies the task to the given subproject, writing the task output to a file
  in the given directory."
  [monolith subproject task out-dir results]
  (let [out-file (io/file out-dir (:group subproject) (str (:name subproject) ".txt"))]
    (io/make-parents out-file)
    (with-open [file-output-stream (io/output-stream out-file :append true)]
      ; Write task header
      (.write file-output-stream
              (.getBytes (format "[%s] Applying task to %s/%s: %s\n\n"
                                 (java.util.Date.)
                                 (:group subproject)
                                 (:name subproject)
                                 (str/join " " task))))
      (try
        ; Run task with output capturing.
        (binding [*task-file-output* file-output-stream]
          (with-redefs [leiningen.core.eval/sh run-with-output]
            (apply-subproject-task monolith subproject task)))
        (finally
          ; Write task footer
          (.write file-output-stream
                  (.getBytes (format "\n[%s] Elapsed: %s\n"
                                     (java.util.Date.)
                                     (u/human-duration (:elapsed @results))))))))))


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
      (lein/info (format "\nApplying to %s" (ansi/maybe-sgr target :bold :yellow)))
      (if-let [out-dir (get-in ctx [:opts :output] )]
        ; Capture output to file.
        (apply-subproject-task-with-output (:monolith ctx) subproject (:task ctx) out-dir results)
        ; Run without output capturing.
        (apply-subproject-task (:monolith ctx) subproject (:task ctx)))
      (assoc @results :success true)
      (catch Exception ex
        (when-not (or (:parallel opts) (:endure opts))
          (let [resume-args (concat
                              ["lein monolith each"]
                              (opts->args (dissoc opts :start))
                              [:start target]
                              (:task ctx))]
            (lein/warn (format "\n%s %s\n"
                               (ansi/maybe-sgr "Resume with:" :bold :red)
                               (str/join " " resume-args)))))
        (when-not (:endure opts)
          (throw ex))
        (assoc @results :success false, :error ex))
      (finally
        (lein/info (format "Completed %s (%s/%s) in %s"
                           (ansi/maybe-sgr target :bold :yellow)
                           (ansi/maybe-sgr (swap! (:completions ctx) inc) :cyan)
                           (ansi/maybe-sgr (:num-targets ctx) :cyan)
                           (ansi/maybe-sgr (u/human-duration (:elapsed @results)) :bold :cyan)))))))


(defn- run-linear!
  "Runs the task for each target in a linear (single-threaded) fashion. Returns
  a vector of result maps in the order the tasks were executed."
  [ctx targets]
  (mapv (comp (partial run-task! ctx) second) targets))


(defn- run-parallel!
  "Runs the tasks for targets in multiple worker threads, chained by dependency
  order. Returns a vector of result maps in the order the tasks finished executing."
  [ctx threads targets]
  (let [task-name (first (:task ctx))
        deps (partial dep/upstream-keys (dep/dependency-map (:subprojects ctx)))
        thread-pool (executor/fixed-thread-executor threads)]
    ; Perform an initial resolution of the task to prevent metadata-related
    ; arglist errors when namespaces are loaded in parallel.
    (lein/resolve-task (first (:task ctx)))
    (->
      (reduce
        (fn future-builder
          [computations [i target]]
          (let [upstream-futures (keep computations (deps target))
                task-runner (fn task-runner
                              [dependency-results]
                              (d/future-with thread-pool
                                (lein/debug "Starting project" target)
                                (run-task! ctx target)))
                task-future (if (seq upstream-futures)
                              (d/chain (apply d/zip upstream-futures) task-runner)
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
    (when (empty? targets)
      (lein/abort "Target selection matched zero subprojects!"))
    (lein/info "Applying"
               (ansi/maybe-sgr (str/join " " task) :bold :cyan)
               "to" (ansi/maybe-sgr (count targets) :cyan)
               "subprojects...")
    (let [ctx {:monolith monolith
               :subprojects subprojects
               :completions (atom (ffirst targets))
               :num-targets n
               :task task
               :opts opts}
          results (if-let [threads (:parallel opts)]
                    (run-parallel! ctx (Integer/parseInt threads) targets)
                    (run-linear! ctx targets))
          elapsed (/ (- (System/nanoTime) start-time) 1000000.0)]
      (when (:report opts)
        (print-report results elapsed))
      (if-let [failures (seq (map :name (remove :success results)))]
        (lein/abort (format "\n%s: Applied %s to %s projects in %s with %d failures: %s"
                            (ansi/maybe-sgr "FAILURE" :bold :red)
                            (ansi/maybe-sgr (str/join " " task) :bold :cyan)
                            (ansi/maybe-sgr (count targets) :cyan)
                            (u/human-duration elapsed)
                            (count failures)
                            (str/join " " failures)))
        (lein/info (format "\n%s: Applied %s to %s projects in %s"
                           (ansi/maybe-sgr "SUCCESS" :bold :green)
                           (ansi/maybe-sgr (str/join " " task) :bold :cyan)
                           (ansi/maybe-sgr (count targets) :cyan)
                           (u/human-duration elapsed)))))))
