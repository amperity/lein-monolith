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


(defn ^:no-project-needed ^:higher-order each
  "Iterate over each subproject in the monolith and apply the given task.
  Projects are iterated in dependency order; that is, later projects may depend
  on earlier ones.

  Example:

      lein monolith each check"
  [project task-name & args]
  (let [config (config/read!)
        subprojects (get-subprojects project config)
        n (count subprojects)
        start (System/nanoTime)]
    (lein/info "Applying" task-name "to" (ansi/sgr n :cyan) "subprojects...")
    (doseq [[i subproject-name] (map vector (range) (dependency-order subprojects))]
      (lein/info (format "\nApplying to %s (%s/%s)"
                         (ansi/sgr subproject-name :bold :yellow)
                         (ansi/sgr (inc i) :cyan)
                         (ansi/sgr n :cyan)))
      (lein/apply-task task-name (get subprojects subproject-name) args))
    (lein/info (format "\n%s: Applied %s to %s projects in %.3f seconds"
                       (ansi/sgr "SUCCESS" :bold :green)
                       task-name
                       (ansi/sgr n :cyan)
                       (/ (- (System/nanoTime) start) 1000000000.0M)))))


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
      (lein/debug "Creating checkout directory" checkouts-dir)
      (.mkdir checkouts-dir))
    ; Check each dependency for internal projects.
    (doseq [spec (:dependencies project)]
      (when-let [subproject (get subprojects (u/condense-name (first spec)))]
        (link-checkout! checkouts-dir subproject (flags ":force"))))))


#_ ; TODO: determine if needed
(defn check-deps
  "Check the versions of external dependencies of the current project.

  Options:
    :unlocked    Print warnings for external dependencies with no specified version
    :strict      Exit with a failure status if any versions don't match"
  [project args]
  (let [config (config/read!)
        subprojects (config/load-subprojects! config)
        flags (set args)
        ext-deps (->> (:external-dependencies config)
                      (map (juxt first identity))
                      (into {}))
        error-flag (atom false)]
    (doseq [dependency (:dependencies project)
            :let [depname (u/condense-name (first dependency))
                  spec (vec (cons depname (rest dependency)))]]
      (when-not (get subprojects depname)
        (if-let [expected (ext-deps depname)]
          (when-not (= expected spec)
            (lein/warn "ERROR: External dependency" (pr-str spec)
                       "does not match expected spec" (pr-str expected))
            (when (flags ":strict")
              (reset! error-flag true)))
          (when (flags ":unlocked")
            (lein/warn "WARN: External dependency" (pr-str depname)
                       "has no expected version defined")))))
    (when @error-flag
      (lein/abort))))



;; ## Plugin Entry

(defn ^:no-project-needed monolith
  "Tasks for working with Leiningen projects inside a monorepo."
  {:subtasks [#'info #'link #'each #'with-all]}
  [project command & args]
  (case command
    "info"       (info project args)
    "link"       (link project args)
    "each"       (apply each project args)
    "with-all"   (apply with-all project args)
    (lein/abort (pr-str command) "is not a valid monolith command! Try: lein help monolith"))
  (flush))
