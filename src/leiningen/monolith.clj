(ns leiningen.monolith
  "Leiningen task implementations for working with monorepos."
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]
    (leiningen.core
      [main :as lein]
      [project :as project])
    (lein-monolith
      [config :as config]
      [plugin :as plugin]
      [util :as u])
    [puget.printer :as puget]
    [puget.color.ansi :as ansi])
  (:import
    (java.io
      File)
    (java.nio.file
      Files
      LinkOption
      Paths)))


(defn- dependency-order
  "Returns a sequence of project names in dependency order, determined from the
  input subproject map. Later projects in the sequence may depend on earlier
  ones."
  [projects]
  (->> projects
       (u/map-vals #(set (map (comp u/condense-name first)
                              (:dependencies %))))
       (u/topological-sort)))


(defn- get-subprojects
  "Attempts to look up the subprojects definitions in the project map, in case
  they were already loaded by the `:monolith/all` profile. Otherwise, loads them
  directly using the config."
  [project config]
  (or (:monolith/subprojects project)
      (config/load-subprojects! config)))


(defn- create-symlink!
  "Creates a link from the given source path to the given target."
  [source target]
  (Files/createSymbolicLink
    source target
    (make-array java.nio.file.attribute.FileAttribute 0)))


(defn- link-checkout!
  "Creates a checkout dependency link to the given subproject."
  [^File checkouts-dir subproject force?]
  (let [dep-root (jio/file (:root subproject))
        dep-name (u/condense-name (symbol (:group subproject)
                                          (:name subproject)))
        link-name (if (namespace dep-name)
                    (str (namespace dep-name) "~" (name dep-name))
                    (name dep-name))
        link-path (.toPath (jio/file checkouts-dir link-name))
        target-path (.relativize (.toPath checkouts-dir) (.toPath dep-root))]
    (if (Files/exists link-path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      ; Link file exists.
      (let [actual-target (Files/readSymbolicLink link-path)]
        (if (and (Files/isSymbolicLink link-path)
                 (= target-path actual-target))
          ; Link exists and points to target already.
          (lein/info "Link for" dep-name "is correct")
          ; Link exists but points somewhere else.
          (if force?
            ; Recreate link since force is set.
            (do (lein/warn "Relinking" dep-name "from"
                           (str actual-target) "to" (str target-path))
                (Files/delete link-path)
                (create-symlink! link-path target-path))
            ; Otherwise print a warning.
            (lein/warn "WARN:" dep-name "links to" (str actual-target)
                       "instead of" (str target-path)))))
      ; Link does not exist, so create it.
      (do (lein/info "Linking" dep-name "to" (str target-path))
          (create-symlink! link-path target-path)))))



;; ## Subtask Implementations

(defn ^:no-project-needed info
  "Show information about the monorepo configuration.

  Options:
    :bare        Only print the project names and directories, one per line"
  [project args]
  (let [bare? (some #{":bare"} args)
        config (config/read!)]
    (when-not bare?
      (println "Monolith root:" (:mono-root config))
      (println "Subproject directories:")
      (puget/cprint (:project-dirs config))
      (println))
    (let [subprojects (get-subprojects project config)
          prefix-len (inc (count (:mono-root config)))]
      (when-not bare?
        (printf "Internal projects (%d):\n" (count subprojects)))
      (doseq [subproject-name (dependency-order subprojects)
              :let [{:keys [version root]} (get subprojects subproject-name)
                    relative-path (subs (str root) prefix-len)]]
        (if bare?
          (println subproject-name relative-path)
          (printf "  %-80s   %s\n"
                  (puget/cprint-str [subproject-name version])
                  relative-path))))))


(defn ^:no-project-needed uses
  "Print a list of subprojects which depend on the given package(s)."
  [project args]
  (let [config (config/read!)
        subprojects (get-subprojects project config)]
    (doseq [dep-name (map read-string args)]
      (lein/info "\nSubprojects which use" (ansi/sgr dep-name :bold :yellow))
      (doseq [subproject-name (dependency-order subprojects)
              :let [{:keys [version dependencies]} (get subprojects subproject-name)]]
        (when-let [spec (first (filter (comp #{dep-name} first) dependencies))]
          (printf "  %-80s -> %s\n"
                  (puget/cprint-str [subproject-name version])
                  (puget/cprint-str spec)))))))


(defn ^:no-project-needed ^:higher-order each
  "Iterate over each subproject in the monolith and apply the given task.
  Projects are iterated in dependency order; that is, later projects may depend
  on earlier ones.

  If the iteration fails on a subproject, you can continue where you left off
  by providing the `:start` option as the first argument, giving the name of the
  project to resume from.

  Examples:

      lein monolith each check
      lein monolith each :start my/lib-a test"
  [project & args]
  (let [config (config/read!)
        subprojects (get-subprojects project config)
        n (count subprojects)
        [start-from [task-name & args]]
        (if (= ":start" (first args))
          [(read-string (second args)) (drop 2 args)]
          [nil args])
        start-time (System/nanoTime)
        ordered-names (cond->> (map-indexed vector (dependency-order subprojects))
                        start-from
                          (drop-while (comp (partial not= start-from) second)))]
    (lein/info "Applying" task-name "to" (ansi/sgr (count ordered-names) :cyan)
               "subprojects...")
    (doseq [[i subproject-name] ordered-names]
      (lein/info (format "\nApplying to %s (%s/%s)"
                         (ansi/sgr subproject-name :bold :yellow)
                         (ansi/sgr (inc i) :cyan)
                         (ansi/sgr n :cyan)))
      (lein/apply-task task-name (get subprojects subproject-name) args))
    (lein/info (format "\n%s: Applied %s to %s projects in %.3f seconds"
                       (ansi/sgr "SUCCESS" :bold :green)
                       task-name
                       (ansi/sgr n :cyan)
                       (/ (- (System/nanoTime) start-time) 1000000000.0M)))))


(defn ^:higher-order with-all
  "Apply the given task with a merged set of dependencies, sources, and tests
  from all the internal projects.

  For example:

      lein monolith with-all test"
  [project task-name & args]
  (when (:monolith project)
    (lein/abort "Running 'with-all' in a monolith project is redundant!"))
  (lein/apply-task
    task-name
    (-> project
        (plugin/add-profile)
        (plugin/activate-profile))
    args))


(defn link
  "Create symlinks in the checkouts directory pointing to all internal
  dependencies in the current project.

  Options:
    :force       Override any existing checkout links with conflicting names"
  [project args]
  (when-not project
    (lein/abort "The 'link' task requires a project to run in"))
  (when (:monolith project)
    (lein/abort "The 'link' task does not need to be run for the monolith project!"))
  (let [flags (set args)
        config (config/read!)
        subprojects (get-subprojects project config)
        checkouts-dir (jio/file (:root project) "checkouts")]
    ; Create checkouts directory if needed.
    (when-not (.exists checkouts-dir)
      (lein/info "Creating checkout directory" checkouts-dir)
      (.mkdir checkouts-dir))
    ; Check each dependency for internal projects.
    (doseq [spec (:dependencies project)]
      (when-let [subproject (get subprojects (u/condense-name (first spec)))]
        (link-checkout! checkouts-dir subproject (flags ":force"))))))


(defn unlink
  "Remove the checkout directory from a project."
  [project]
  (when-let [checkouts-dir (some-> (:root project) (jio/file "checkouts"))]
    (when (.exists checkouts-dir)
      (lein/info "Removing checkout directory" (str checkouts-dir))
      (doseq [link (.listFiles checkouts-dir)]
        (lein/debug "Removing checkout link" (str link))
        (.delete ^File link))
      (.delete checkouts-dir))))



;; ## Plugin Entry

(defn ^:no-project-needed monolith
  "Tasks for working with Leiningen projects inside a monorepo."
  {:subtasks [#'info #'uses #'each #'with-all #'link #'unlink]}
  [project command & args]
  (case command
    "info"       (info project args)
    "uses"       (uses project args)
    "each"       (apply each project args)
    "with-all"   (apply with-all project args)
    "link"       (link project args)
    "unlink"     (unlink project)
    (lein/abort (pr-str command) "is not a valid monolith command! Try: lein help monolith"))
  (flush))
