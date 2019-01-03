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
      [plugin :as plugin]
      [target :as target])
    (lein-monolith.task
      [checkouts :as checkouts]
      [each :as each]
      [fingerprint :as fingerprint]
      [graph :as graph]
      [info :as info]
      [util :as u])
    [puget.printer :as puget]
    [puget.color.ansi :as ansi]))


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
    :bare        Only print the project names and directories, one per line"
  [project args]
  (info/info project (opts-only (merge target/selection-opts {:bare 0}) args)))


(defn lint
  "Check various aspects of the monolith and warn about possible problems.

  Options:
    :deps        Check for conflicting dependency versions"
  [project args]
  (info/lint project (if (seq args) (opts-only {:deps 0} args) {:deps true})))


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
  "Generate a graph of subprojects and their interdependencies."
  [project]
  (graph/graph project))


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
        profile (plugin/merged-profile (select-keys subprojects targets))]
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
    :parallel <threads>        Run tasks in parallel across a fixed thread pool.
    :endure                    Continue executing the task even if some subprojects fail.
    :report                    Print a detailed timing report after running tasks.
    :output <path>             Save each project's individual output in the given directory.

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
    (lein/abort "The 'link' task does not need to be run for the monolith project!"))
  (let [[opts project-names] (opts+projects {:force 0, :deep 0} project args)]
    (checkouts/link project opts project-names)))


(defn unlink
  "Remove the checkouts directory from a project."
  [project]
  (checkouts/unlink project))


;; ## Fingerprinting

;; Leiningen's help framework doesn't officially support "subsubtasks" so
;; we'll try our best to make it work by making them look like subtasks.

(defn changed
  "Show information about the projects that have changed since last :refresh.

  Optionally takes one or more marker ids, or project selectors, to narrow the
  information.

  Usage:
  lein monolith changed [project-selectors] [marker1 marker2 ...]"
  [project args]
  (let [[opts more] (u/parse-kw-args fingerprint/selection-opts args)
        opts (u/globalize-opts project opts)]
    (fingerprint/changed project opts more)))


(defn mark
  "Manually mark projects as refreshed.

  Fingerprints all projects, or a selected set of projects, and saves the
  results under the given marker id(s), for later use with the `:refresh`
  selector.

  Usage:
  lein monolith mark [project-selectors] marker1 marker2 ..."
  [project args]
  (let [[opts more] (u/parse-kw-args fingerprint/selection-opts args)
        opts (u/globalize-opts project opts)]
    (fingerprint/mark project opts more)))


(defn clear
  "Clear projects' cached state so they will be re-built next :refresh.

  Removes the fingerprints associated with one or more marker types on one or
  more projects. By default, clears all projects for all marker types.

  Usage:
  lein monolith clear [project-selectors] [marker1 marker2 ...]"
  [project args]
  (let [[opts more] (u/parse-kw-args fingerprint/selection-opts args)
        opts (u/globalize-opts project opts)]
    (fingerprint/clear project opts more)))


;; ## Plugin Entry

(defn monolith
  "Tasks for working with Leiningen projects inside a monorepo."
  {:subtasks [#'info #'lint #'deps-on #'deps-of #'graph
              #'with-all #'each #'link #'unlink
              #'changed #'mark #'clear]}
  [project command & args]
  (case command
    "info"       (info project args)
    "lint"       (lint project args)
    "deps-on"    (deps-on project args)
    "deps-of"    (deps-of project args)
    "graph"      (graph project)
    "with-all"   (with-all project args)
    "each"       (each project args)
    "link"       (link project args)
    "unlink"     (unlink project)
    "changed"    (changed project args)
    "mark"       (mark project args)
    "clear"      (clear project args)
    (lein/abort (pr-str command) "is not a valid monolith command! Try: lein help monolith"))
  (flush))
