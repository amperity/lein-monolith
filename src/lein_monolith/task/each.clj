(ns lein-monolith.task.each
  (:require
    [clojure.java.io :as io]
    [clojure.stacktrace :as cst]
    [clojure.string :as str]
    [lein-monolith.color :refer [colorize]]
    [lein-monolith.config :as config]
    [lein-monolith.dependency :as dep]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.target :as target]
    [lein-monolith.task.fingerprint :as fingerprint]
    [lein-monolith.task.util :as u]
    [leiningen.core.eval :as eval]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [leiningen.core.utils :refer [rebind-io!]]
    [leiningen.do :as lein-do]
    [manifold.deferred :as d]
    [manifold.executor :as executor])
  (:import
    (com.hypirion.io
      ClosingPipe
      Pipe
      RevivableInputStream)
    java.io.OutputStream))


(def task-opts
  (merge
    target/selection-opts
    {:parallel 1
     :endure 0
     :report 0
     :output 1
     :upstream 0
     :downstream 0
     :start 1
     :changed 1
     :refresh 1}))


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
    (if-let [refresh (:refresh opts)]
      [:refresh refresh]
      (when-let [changed (:changed opts)]
        [:changed changed]))
    (when-let [start (:start opts)]
      [:start start])))


(defn- print-report
  "Reports information about the tasks given a results map."
  [results elapsed]
  (let [task-time (reduce + (keep :elapsed results))
        speedup (/ task-time elapsed)]
    (lein/info (format "\n%s  %11s"
                       (colorize [:bold :cyan] "Run time:")
                       (u/human-duration elapsed)))
    (lein/info (format "%s %11s"
                       (colorize [:bold :cyan] "Task time:")
                       (u/human-duration task-time)))
    (lein/info (format "%s   %11.1f"
                       (colorize [:bold :cyan] "Speedup:")
                       speedup))
    (lein/info (->> results
                    (sort-by :elapsed)
                    (reverse)
                    (take 8)
                    (map #(format "%-45s %s %11s"
                                  (colorize [:bold :yellow] (:name %))
                                  (if (:success %) " " "!")
                                  (u/human-duration (:elapsed %))))
                    (str/join "\n")
                    (str \newline
                         (colorize [:bold :cyan] "Slowest projects:")
                         \newline)))))


(defn- select-projects
  "Returns a vector of pairs of index numbers and symbols naming the selected
  subprojects."
  [monolith subprojects fprints project-name opts]
  (let [dependencies (dep/dependency-map subprojects)
        targets (target/select monolith subprojects opts)
        start-from (some->> (:start opts)
                            (read-string)
                            (dep/resolve-name! (keys subprojects)))
        marker (:changed opts)]
    (->
      ; Sort project names by dependency order.
      (dep/topological-sort dependencies targets)
      (cond->>
        ; Skip projects until the starting project, if provided.
        start-from (drop-while (partial not= start-from))
        ; Skip projects whose fingerprint hasn't changed.
        marker (filter (partial fingerprint/changed? fprints marker)))
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
  (let [cmd (into-array String cmd)
        env (into-array String (@#'eval/overridden-env eval/*env*))
        proc (.exec (Runtime/getRuntime)
                    ^{:tag "[Ljava.lang.String;"} cmd
                    ^{:tag "[Ljava.lang.String;"} env
                    (io/file eval/*dir*))]
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


(def ^:private init-lock
  "An object to lock on to ensure that projects are not initialized
  concurrently. This prevents the mysterious 'unbound fn' errors that sometimes
  crop up during parallel execution."
  (Object.))


(defn- apply-subproject-task
  "Applies the task to the given subproject."
  [monolith subproject task]
  (binding [lein/*exit-process?* false]
    (let [inherited (plugin/build-inherited-profiles monolith subproject)]
      (as-> subproject
        subproject
        (reduce-kv
          (fn inject-profile [p k v] (assoc-in p [:profiles k] v))
          subproject inherited)
        (config/debug-profile "init-subproject"
          (locking init-lock
            (project/init-project subproject (cons :default (keys inherited)))))
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
          (apply-subproject-task monolith subproject task))
        (catch Exception ex
          (.write file-output-stream
                  (.getBytes (format "\nERROR: %s\n%s"
                                     (ex-message ex)
                                     (with-out-str
                                       (cst/print-cause-trace ex)))))
          (throw ex))
        (finally
          ; Write task footer
          (.write file-output-stream
                  (.getBytes (format "\n[%s] Elapsed: %s\n"
                                     (java.util.Date.)
                                     (u/human-duration (:elapsed @results))))))))))


(defn- resolve-tasks
  "Perform an initial resolution of the task to prevent metadata-related
  arglist errors when namespaces are loaded in parallel."
  [task+args]
  (let [task (first task+args)]
    (lein/resolve-task task)
    ;; Some tasks pull in other tasks, so also resolve them.
    (condp = task
      "do"
      (doseq [subtask+args (lein-do/group-args (rest task+args))]
        (resolve-tasks subtask+args))

      "update-in"
      (let [subtask+args (rest (drop-while #(not= "--" %) task+args))]
        (resolve-tasks subtask+args))

      "with-profile"
      (let [subtask+args (drop 2 task+args)]
        (resolve-tasks subtask+args))

      ;; default no-op
      nil)))


(defn- run-task!
  "Runs the given task, returning a map of information about the run."
  [ctx target]
  ; Try to reclaim some memory before running the task.
  (System/gc)
  (let [start (System/nanoTime)
        opts (:opts ctx)
        subproject (get-in ctx [:subprojects target])
        results (delay {:name target
                        :elapsed (/ (- (System/nanoTime) start) 1000000.0)})
        marker (:changed opts)
        fprints (:fingerprints ctx)]
    (try
      (lein/info (format "\nApplying to %s%s"
                         (colorize [:bold :yellow] target)
                         (if marker
                           (str " (" (fingerprint/explain-str fprints marker target) ")")
                           "")))
      (if-let [out-dir (get-in ctx [:opts :output])]
        ; Capture output to file.
        (apply-subproject-task-with-output (:monolith ctx) subproject (:task ctx) out-dir results)
        ; Run without output capturing.
        (apply-subproject-task (:monolith ctx) subproject (:task ctx)))
      (when (:refresh opts)
        (fingerprint/save! fprints marker target)
        (lein/info (format "Saved %s fingerprint for %s"
                           (colorize :bold marker)
                           (colorize [:bold :yellow] target))))
      (assoc @results :success true)
      (catch Exception ex
        (when-not (or (:parallel opts) (:endure opts))
          (let [resume-args (concat
                              ["lein monolith each"]
                              (opts->args (dissoc opts :start))
                              [:start target]
                              (:task ctx))]
            (lein/warn (format "\n%s %s\n"
                               (colorize [:bold :red] "Resume with:")
                               (str/join " " resume-args)))))
        (if (:endure opts)
          (lein/warn (format "\n%s: %s\n%s"
                             (colorize [:bold :red] "ERROR")
                             (ex-message ex)
                             (with-out-str
                               (cst/print-cause-trace ex))))
          (throw ex))
        (assoc @results :success false, :error ex))
      (finally
        (lein/info (format "Completed %s (%s/%s) in %s"
                           (colorize [:bold :yellow] target)
                           (colorize :cyan (swap! (:completions ctx) inc))
                           (colorize :cyan (:num-targets ctx))
                           (colorize [:bold :cyan] (u/human-duration (:elapsed @results)))))))))


(defn- run-linear!
  "Runs the task for each target in a linear (single-threaded) fashion. Returns
  a vector of result maps in the order the tasks were executed."
  [ctx targets]
  (mapv (comp (partial run-task! ctx) second) targets))


(defn- run-parallel*
  "Internal helper for `run-parallel!` which sets up the actual project threads."
  [ctx threads targets]
  (let [task-name (first (:task ctx))
        deps (partial dep/upstream-keys (dep/dependency-map (:subprojects ctx)))
        thread-pool (executor/fixed-thread-executor threads)]
    (resolve-tasks (:task ctx))
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


(defn- run-parallel!
  "Runs the tasks for targets in multiple worker threads, chained by dependency
  order. Returns a vector of result maps in the order the tasks finished executing."
  [ctx threads targets]
  (if (get-in ctx [:opts :output])
    ;; NOTE: this is done here rather than inside each task so that tasks
    ;; starting across threads don't have a chance to see the `sh` var between
    ;; rebindings.
    (with-redefs [leiningen.core.eval/sh run-with-output]
      (run-parallel* ctx threads targets))
    (run-parallel* ctx threads targets)))


(defn run-tasks
  "Iterate over each subproject in the monolith and apply the given task."
  [project opts task]
  (let [[monolith subprojects] (u/load-monolith! project)
        fprints (fingerprint/context monolith subprojects)
        opts (if-let [marker (:refresh opts)]
               (-> opts
                   (assoc :changed marker))
               opts)
        targets (select-projects
                  monolith subprojects fprints
                  (dep/project-name project)
                  (u/globalize-opts project opts))
        n (inc (or (first (last targets)) -1))
        start-time (System/nanoTime)]
    (if (empty? targets)
      (lein/info "Target selection matched zero subprojects; nothing to do")
      (do
        (lein/info "Applying"
                   (colorize [:bold :cyan] (str/join " " task))
                   "to" (colorize :cyan (count targets))
                   "subprojects...")
        (let [ctx {:monolith monolith
                   :subprojects subprojects
                   :fingerprints fprints
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
                                (colorize [:bold :red] "FAILURE")
                                (colorize [:bold :cyan] (str/join " " task))
                                (colorize :cyan (count targets))
                                (u/human-duration elapsed)
                                (count failures)
                                (str/join " " failures)))
            (lein/info (format "\n%s: Applied %s to %s projects in %s"
                               (colorize [:bold :green] "SUCCESS")
                               (colorize [:bold :cyan] (str/join " " task))
                               (colorize :cyan (count targets))
                               (u/human-duration elapsed)))))))))
