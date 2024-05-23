(ns lein-monolith.task.with-dependency-set
  (:require
    [clojure.java.io :as io]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.task.util :as u]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]))


(defn- reload-project
  "Reloads the raw project.clj file for the given project.
   This has the effect of removing any middleware that was added to the project
   as well as any changes to the project map that were made in the current
   session."
  [project]
  (let [project-file (io/file (:root project) "project.clj")]
    (project/read-raw (str project-file))))


(defn- change-dependencies
  [project dependency-set]
  (let [[monolith _] (u/load-monolith! project)
        dependencies (or (get-in monolith [:monolith :dependency-sets dependency-set])
                         (lein/abort (format "Unknown dependency set %s" dependency-set)))]
    (if (:monolith project)
      (assoc project :managed-dependencies dependencies)
      (assoc project :monolith/dependency-set dependency-set))))


(defn- setup
  [project dependency-set]
  (-> project
      (reload-project)
      (change-dependencies dependency-set)
      (plugin/add-middleware)
      (project/init-project)))


(defn run-task
  "Runs the given task on the project with the given dependency set by reloading
   the project, changing the dependencies, re-initializing the project, and then
   running the task."
  [project dependency-set task]
  (-> project
      (setup dependency-set)
      (lein/resolve-and-apply task)))
