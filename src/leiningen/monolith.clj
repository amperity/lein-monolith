(ns leiningen.monolith
  "Leiningen task implementations for working with monorepos."
  (:require
    [clojure.string :as str]
    [lein-monolith.dependency :as dep]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.target :as target]
    [lein-monolith.task.checkouts :as checkouts]
    [lein-monolith.task.each :as each]
    [lein-monolith.task.fingerprint :as fingerprint]
    [lein-monolith.task.graph :as graph]
    [lein-monolith.task.info :as info]
    [lein-monolith.task.util :as u]
    [lein-monolith.task.with-dependency-set :as wds]
    [leiningen.core.main :as lein]))


(defn- opts-only
  [expected args]
  (let [[opts more] (u/parse-kw-args expected args)]
    (when (seq more)
      (lein/abort "Unknown args:" (str/join " " more)))
    opts))


(defn- opts+projects
  [expected project args]
  (let [[opts more] (u/parse-kw-args expected args)
        project-names (or (seq (map read-string more))
                          [(dep/project-name project)])]
    [opts project-names]))


;; ## Subtask Vars

(defn info
  "Show information about the monorepo configuration.

  Options:
    :bare        Only print the project names and directories, one per line
    (targets)    Standard target selection options are supported"
  [project args]
  (info/info project (opts-only (merge target/selection-opts {:bare 0}) args)))


(defn lint
  "Check various aspects of the monolith and warn about possible problems.

  Options:
    :deps        Check for conflicting dependency versions"
  [project args]
  (info/lint project (if (seq args) (opts-only {:deps 0} args) {:deps true})))


(defn deps
  "Print a list of subprojects and the (internal) projects they depend on.
  Targeting options may be used to scope down the projects listed.

  Options:
    :internal <bool>   Whether to show dependencies on internal projects (default: true)
    :external <bool>   Whether to show dependencies on external projects (default: false)
    :bare              Only print the project names and dependencies, one per line
    (targets)          Standard target selection options are supported"
  [project args]
  (let [opts (opts-only (assoc target/selection-opts
                               :internal 1
                               :external 1
                               :bare 0)
                        args)]
    (info/deps project opts)))


(defn deps-on
  "Print a list of subprojects which depend on the given package(s). Defaults
  to the current project if none are provided.

  Options:
    :bare          Only print the project names and dependent versions, one per line"
  [project args]
  (let [[opts project-names] (opts+projects {:bare 0} project args)]
    (info/deps-on project opts project-names)))


(defn deps-of
  "Print a list of subprojects which given package(s) depend on. Defaults to
  the current project if none are provided.

  Options:
    :bare          Only print the project names and dependent versions, one per line
    :transitive    Include transitive dependencies in addition to direct ones"
  [project args]
  (let [[opts project-names] (opts+projects {:bare 0} project args)]
    (info/deps-of project opts project-names)))


(defn graph
  "Generate a graph of subprojects and their interdependencies.

  Options:
    :image-path <path>   Path to save the graph image to (default: `project-hierarchy.png`)
    :dot-path <path>     Path to save the raw dot file to (default: not output)
    (targets)            Standard target selection options are supported"
  [project args]
  (graph/graph
    project
    (opts-only (assoc target/selection-opts
                      :image-path 1
                      :dot-path 1)
               args)))


(defn ^:higher-order with-all
  "Apply the given task with a merged set of dependencies, sources, and tests
  from all the internal projects.

  For example:

      lein monolith with-all test"
  [project args]
  (when-not (:monolith project)
    (lein/warn "WARN: Running with-all in a subproject is not recommended! Beware of dependency ordering differences."))
  (let [[opts [task & args]] (u/parse-kw-args target/selection-opts args)
        [monolith subprojects] (u/load-monolith! project)
        targets (target/select monolith subprojects opts)
        profile (plugin/merged-profile monolith (select-keys subprojects targets))
        project (reduce-kv
                  (fn remove-replace-meta
                    [proj k _v]
                    (update proj k vary-meta dissoc :replace))
                  project profile)]
    (lein/apply-task
      task
      (plugin/add-active-profile project :monolith/all profile)
      args)))


(defn ^:higher-order each
  "Iterate over a target set of subprojects in the monolith and apply the given
  task. Projects are iterated in dependency order; that is, later projects may
  depend on earlier ones.

  By default, all projects are included in the set of iteration targets. If you
  provide the `:in`, `:upstream[-of]`, or `:downstream[-of]` options then the
  resulting set of projects will be composed only of the additive targets of
  each of the options specified. The `:skip` option can be used to exclude
  specific projects from the set. Specifying `:select` will use a configured
  `:project-selector` to filter the final set.

  If the iteration fails on a subproject, you can continue where you left off
  by providing the `:start` option as the first argument, giving the name of the
  project to resume from.

  General Options:
    :parallel <threads>     Run tasks in parallel across a fixed thread pool.
    :endure                 Continue executing the task even if some subprojects fail.
    :report                 Print a detailed timing report after running tasks.
    :silent                 Don't print task output unless a subproject fails.
    :output <path>          Save each project's individual output in the given directory.

  Targeting Options:
    :in <names>             Add the named projects directly to the targets.
    :upstream               Add the transitive dependencies of the current project to the targets.
    :upstream-of <names>    Add the transitive dependencies of the named projects to the targets.
    :downstream             Add the transitive consumers of the current project to the targets.
    :downstream-of <names>  Add the transitive consumers of the named projects to the targets.
    :select <key>           Use a selector from the config to filter target projects.
    :skip <names>           Exclude one or more projects from the target set.
    :start <name>           Provide a starting point for the subproject iteration
    :refresh <marker>       Only iterate over projects that have changed since the last `:refresh` of this marker
    :changed <marker>       Like `:refresh` but does not reset the projects' state for the next run

  Each <names> argument can contain multiple comma-separated project names, and
  all the targeting options except `:start` may be provided multiple times.

  Examples:

      lein monolith each check
      lein monolith each :upstream :parallel 4 install
      lein monolith each :select :deployable uberjar
      lein monolith each :report :start my/lib-a test
      lein monolith each :refresh ci/build install"
  [project args]
  (let [[opts task] (u/parse-kw-args each/task-opts args)]
    (when (empty? task)
      (lein/abort "Cannot run each without a task argument!"))
    (when (and (:start opts) (:parallel opts))
      (lein/abort "The :parallel and :start options are not compatible!"))
    (each/run-tasks project opts task)))


(defn link
  "Create symlinks in the checkouts directory pointing to all internal
  dependencies in the current project. Optionally, a set of project names may
  be specified to create links to only those projects (this implies `:deep`).

  Options:
    :force       Override any existing checkout links with conflicting names
    :deep        Link all subprojects this project transitively depends on"
  [project args]
  (when (:monolith project)
    (lein/abort "The 'link' task cannot be run for the monolith project!"))
  (let [[opts project-names] (opts+projects {:force 0, :deep 0} project args)
        target-names (remove #(= (dep/project-name project) %)
                             project-names)]
    (checkouts/link project opts target-names)))


(defn unlink
  "Remove internal checkout links from a project. Optionally, a set of project
  names may be specified to remove links only for those projects.

  Options:
    :all         Remove all checkouts, not just internal ones."
  [project args]
  (when (:monolith project)
    (lein/abort "The 'unlink' task cannot be run for the monolith project!"))
  (let [[opts project-names] (opts+projects {:all 0} project args)
        target-names (remove #(= (dep/project-name project) %)
                             project-names)]
    (checkouts/unlink project opts target-names)))


;; ## Fingerprinting

(defn changed
  "Show information about the projects that have changed since last :refresh.

  Optionally takes one or more marker ids, or project selectors, to narrow the
  information.

  Usage:
  lein monolith changed [:debug] [project-selectors] [marker1 marker2 ...]"
  [project args]
  (let [[opts more] (u/parse-kw-args fingerprint/task-opts args)
        opts (u/globalize-opts project opts)]
    (fingerprint/changed project opts more)))


(defn mark-fresh
  "Manually mark projects as refreshed.

  Fingerprints all projects, or a selected set of projects, and saves the
  results under the given marker id(s), for later use with the `:refresh`
  selector.

  Usage:
  lein monolith mark [project-selectors] marker1 marker2 ..."
  [project args]
  (let [[opts more] (u/parse-kw-args fingerprint/task-opts args)
        opts (u/globalize-opts project opts)]
    (fingerprint/mark-fresh project opts more)))


(defn show-fingerprints
  "Show information about the calculation of one or more projects'
  fingerprints, compared to a current marker.

  Usage:
  lein monolith show-fingerprints marker project [...]"
  [project [marker & args]]
  (fingerprint/show project marker args))


(defn clear-fingerprints
  "Clear projects' cached fingerprints so they will be re-built next :refresh.

  Removes the fingerprints associated with one or more marker types on one or
  more projects. By default, clears all projects for all marker types.

  Usage:
  lein monolith clear [project-selectors] [marker1 marker2 ...]"
  [project args]
  (let [[opts more] (u/parse-kw-args fingerprint/task-opts args)
        opts (u/globalize-opts project opts)]
    (fingerprint/clear project opts more)))


(defn ^:higher-order with-dependency-set
  "Run a task with a set of managed dependencies from a named dependency set.

   Overrides the dependencies from the named dependency set into the project.
   For the root project, this means the managed dependencies will be overwritten
   with the dependencies from the named set. For subprojects, the
   `:monolith/dependency-set` metadata key will be set to the named set. If the
   project does not have the `:monolith/dependency-set` key defined, it will be
   added.

   A gotcha: this task does not update the project's `project.clj` file, so the
   dependencies will not be saved to disk. This is intentional, as the task is
   meant to be used for temporary operations that need to be run with a specific
   set of dependencies. This also means that the task will not work with tasks
   that require the project file to be updated.

   Options:
     :only    Use _only_ the dependencies from the named set. This replaces
              the `:dependencies` value with an empty list and has the effect
              of _only_ including a project's managed dependencies. This can
              cause issues with tasks that require the project's dependencies
              to be present in the project map (e.g. compilation or others).
              This is useful for running a task that operates only on the
              dependencies values in the project such as vulnerability scanning,
              dependency tree generation, etc.

   Usage:
   lein monolith with-dependency-set [:only] <dependency-set-name> <task> [...]"
  [project args]
  (let [[opts more] (u/parse-kw-args {:only 0} args)
        dependency-set (read-string (first more))]
    (wds/run-task project opts dependency-set (rest more))))


;; ## Plugin Entry

(defn monolith
  "Tasks for working with Leiningen projects inside a monorepo."
  {:subtasks [#'info #'lint #'deps #'deps-on #'deps-of #'graph
              #'with-all #'each #'link #'unlink
              #'changed #'mark-fresh #'show-fingerprints #'clear-fingerprints
              #'with-dependency-set]}
  [project command & args]
  (case command
    "info"                (info project args)
    "lint"                (lint project args)
    "deps"                (deps project args)
    "deps-on"             (deps-on project args)
    "deps-of"             (deps-of project args)
    "graph"               (graph project args)
    "with-all"            (with-all project args)
    "each"                (each project args)
    "link"                (link project args)
    "unlink"              (unlink project args)
    "changed"             (changed project args)
    "mark-fresh"          (mark-fresh project args)
    "show-fingerprints"   (show-fingerprints project args)
    "clear-fingerprints"  (clear-fingerprints project args)
    "with-dependency-set" (with-dependency-set project args)
    (lein/abort (pr-str command) "is not a valid monolith command! Try: lein help monolith"))
  (flush))
