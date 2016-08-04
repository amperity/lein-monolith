(ns leiningen.monolith
  "Leiningen task implementations for working with monorepos."
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.string :as str]
    (leiningen.core
      [main :as lein]
      [project :as project])
    (lein-monolith
      [config :as config]
      [dependency :as dep]
      [plugin :as plugin])
    (lein-monolith.task
      [util :refer [parse-kw-args load-monolith!]])
    [puget.printer :as puget]
    [puget.color.ansi :as ansi])
  (:import
    (java.io
      File)
    (java.nio.file
      Files
      LinkOption
      Paths)))



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
        dep-name (dep/project-name subproject)
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

(defn info
  "Show information about the monorepo configuration.

  Options:
    :bare        Only print the project names and directories, one per line"
  [project args]
  (let [[opts _] (parse-kw-args {:bare 0} args)
        monolith (config/find-monolith! project)]
    (when-not (:bare opts)
      (println "Monolith root:" (:root monolith))
      (println)
      (when-let [inherited (get-in monolith [:monolith :inherit])]
        (println "Inherited properties:")
        (puget/cprint inherited)
        (println))
      (when-let [dirs (get-in monolith [:monolith :project-dirs])]
        (println "Subproject directories:")
        (puget/cprint dirs)
        (println)))
    (let [subprojects (config/read-subprojects! monolith)
          targets (dep/topological-sort (dep/dependency-map subprojects))
          prefix-len (inc (count (:root monolith)))]
      (when-not (:bare opts)
        (printf "Internal projects (%d):\n" (count targets)))
      (doseq [subproject-name targets
              :let [{:keys [version root]} (get subprojects subproject-name)
                    relative-path (subs (str root) prefix-len)]]
        (if (:bare opts)
          (println subproject-name relative-path)
          (printf "  %-90s   %s\n"
                  (puget/cprint-str [subproject-name version])
                  (ansi/sgr relative-path :cyan)))))))


(defn lint
  "Check various aspects of the monolith and warn about possible problems.

  Options:
    :deps        Check for conflicting dependency versions"
  [project args]
  (let [flags (set (or (seq args) [":deps"]))
        [monolith subprojects] (load-monolith! project)]
    ; TODO: lack of :pedantic? :abort
    (when (flags ":deps")
      (doseq [[dep-name coords] (->> (vals subprojects)
                                     (mapcat dep/sourced-dependencies)
                                     (group-by first))]
        (dep/select-dependency dep-name coords)))))


(defn deps-on
  "Print a list of subprojects which depend on the given package(s). Defaults
  to the current project if none are provided.

  Options:
    :bare          Only print the project names and dependent versions, one per line"
  [project args]
  (let [[opts args] (parse-kw-args {:bare 0} args)
        [monolith subprojects] (load-monolith! project)
        dep-map (dep/dependency-map subprojects)]
    (doseq [dep-name (if (seq args)
                       (map read-string args)
                       [(dep/project-name project)])]
      (when-not (:bare opts)
        (lein/info "\nSubprojects which depend on" (ansi/sgr dep-name :bold :yellow)))
      (doseq [subproject-name (dep/topological-sort dep-map)
              :let [{:keys [version dependencies]} (get subprojects subproject-name)]]
        (when-let [spec (first (filter (comp #{dep-name} dep/condense-name first) dependencies))]
          (if (:bare opts)
            (println subproject-name (first spec) (second spec))
            (println "  " (puget/cprint-str subproject-name)
                     "->" (puget/cprint-str spec))))))))


(defn deps-of
  "Print a list of subprojects which given package(s) depend on. Defaults to
  the current project if none are provided.

  Options:
    :bare          Only print the project names and dependent versions, one per line
    :transitive    Include transitive dependencies in addition to direct ones"
  [project args]
  (let [[opts args] (parse-kw-args {:bare 0, :transitive 0} args)
        [monolith subprojects] (load-monolith! project)
        dep-map (dep/dependency-map subprojects)]
    (doseq [project-name (if (seq args)
                           (map read-string args)
                           [(dep/project-name project)])]
      (when-not (get dep-map project-name)
        (lein/abort project-name "is not a valid subproject!"))
      (when-not (:bare opts)
        (lein/info "\nSubprojects which" (ansi/sgr project-name :bold :yellow)
                   (if (:transitive opts)
                     "transitively depends on"
                     "depends on")))
      (doseq [dep (if (:transitive opts)
                    (-> (dep/subtree-from dep-map project-name)
                        (dissoc project-name)
                        (dep/topological-sort))
                    (->> (get-in subprojects [project-name :dependencies])
                         (map first)
                         (filter subprojects)))]
        (if (:bare opts)
          (println project-name dep)
          (println "  " (puget/cprint-str project-name)
                   "->" dep))))))


(defn graph
  "Generate a graph of subprojects and their interdependencies."
  [project]
  (require 'rhizome.viz)
  (let [visualize! (ns-resolve 'rhizome.viz 'save-graph)
        [monolith subprojects] (load-monolith! project)
        dependencies (dep/dependency-map subprojects)
        graph-file (jio/file (:target-path monolith) "project-hierarchy.png")
        path-prefix (inc (count (:root monolith)))]
    (.mkdir (.getParentFile graph-file))
    (visualize!
      (keys dependencies)
      dependencies
      :vertical? false
      :node->descriptor #(array-map :label (name %))
      :node->cluster (fn [id]
                       (when-let [root (get-in subprojects [id :root])]
                         (str/join "/" (butlast (str/split root #"/")))))
      :cluster->descriptor #(array-map :label (subs (str %) path-prefix))
      :filename (str graph-file))
    (lein/info "Generated dependency graph in" (str graph-file))))


(defn ^:higher-order with-all
  "Apply the given task with a merged set of dependencies, sources, and tests
  from all the internal projects.

  For example:

      lein monolith with-all test"
  [project task & args]
  (when (empty? task)
    (lein/abort "Cannot run with-all without task argument!"))
  (when-not (:monolith project)
    (lein/warn "WARN: Running with-all in a subproject is not recommended! Beware of dependency ordering differences."))
  (let [[monolith subprojects] (load-monolith! project)
        profile (plugin/merged-profile subprojects)]
    (lein/apply-task
      task
      (plugin/add-active-profile project :monolith/all profile)
      args)))


(defn ^:higher-order each
  "Iterate over each subproject in the monolith and apply the given task.
  Projects are iterated in dependency order; that is, later projects may depend
  on earlier ones.

  If the iteration fails on a subproject, you can continue where you left off
  by providing the `:start` option as the first argument, giving the name of the
  project to resume from.

  Options:
    :subtree            Only iterate over transitive dependencies of the current project
    :select <key>       Use a selector from the config to filter projects
    :skip <project>     Omit one or more projects from the iteration (may occur multiple times)
    :start <project>    Provide a starting point for the subproject iteration

  Examples:

      lein monolith each check
      lein monolith each :subtree install
      lein monolith each :select :deployable uberjar
      lein monolith each :start my/lib-a test"
  [project & args]
  (let [[opts task] (parse-kw-args {:subtree 0
                                    :select 1
                                    :skip 1
                                    :start 1}
                                   args)]
    (when (empty? task)
      (lein/abort "Cannot run each without task argument!"))
    (let [[monolith subprojects] (load-monolith! project)
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
            (lein/warn (format "\n%s lein monolith each :start %s %s\n"
                               (ansi/sgr "Resume with:" :bold :red)
                               subproject-name (str/join " " task)))
            (throw ex))))
      (lein/info (format "\n%s: Applied %s to %s projects in %.3f seconds"
                         (ansi/sgr "SUCCESS" :bold :green)
                         (ansi/sgr (str/join " " task) :bold :cyan)
                         (ansi/sgr (count targets) :cyan)
                         (/ (- (System/nanoTime) start-time) 1000000000.0M))))))

(defn link
  "Create symlinks in the checkouts directory pointing to all internal
  dependencies in the current project.

  Options:
    :force       Override any existing checkout links with conflicting names
    :deep        Link all subprojects this project transitively depends on"
  [project args]
  (when (:monolith project)
    (lein/abort "The 'link' task does not need to be run for the monolith project!"))
  (let [[opts _] (parse-kw-args {:force 0 :deep 0} args)
        [monolith subprojects] (load-monolith! project)
        dep-map (dep/dependency-map subprojects)
        projects-to-link (as-> (:dependencies project) deps
                           (map (comp dep/condense-name first) deps)
                           (if (:deep opts)
                             (set (mapcat (comp keys (partial dep/subtree-from dep-map)) deps))
                             deps)
                           (keep subprojects deps))
        checkouts-dir (jio/file (:root project) "checkouts")]
    ; Create checkouts directory if needed.
    (when-not (.exists checkouts-dir)
      (lein/info "Creating checkout directory" (str checkouts-dir))
      (.mkdir checkouts-dir))
    ; Check each dependency for internal projects.
    (doseq [subproject projects-to-link]
      (link-checkout! checkouts-dir subproject (:force opts)))))


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

(defn monolith
  "Tasks for working with Leiningen projects inside a monorepo."
  {:subtasks [#'info #'lint #'deps-on #'deps-of #'graph
              #'with-all #'each
              #'link #'unlink]}
  [project command & args]
  (case command
    "info"       (info project args)
    "lint"       (lint project args)
    "deps-on"    (deps-on project args)
    "deps-of"    (deps-of project args)
    "graph"      (graph project)
    "with-all"   (apply with-all project args)
    "each"       (apply each project args)
    "link"       (link project args)
    "unlink"     (unlink project)
    (lein/abort (pr-str command) "is not a valid monolith command! Try: lein help monolith"))
  (flush))
