(ns lein-monolith.task.with-dependency-set
  (:require
    [clojure.java.io :as io]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.task.util :as u]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]))


(defn- maybe-remove-dependencies
  [project opts]
  (if (:only opts)
    (assoc project :dependencies '())
    project))


(defn- reload-project
  "Reloads the raw project.clj file for the given project.
   This has the effect of removing any middleware that was added to the project
   as well as any changes to the project map that were made in the current
   session."
  [project]
  (-> project
      (:root)
      (io/file "project.clj")
      (str)
      (project/read-raw)))


(defn- change-dependencies
  [project dependency-set]
  (let [[monolith _] (u/load-monolith! project)
        dependencies (plugin/get-dependencies-from-set! monolith dependency-set project)]
    (if (:monolith project)
      (assoc project :managed-dependencies dependencies)
      (assoc project :monolith/dependency-set dependency-set))))


(defn run-task
  "Runs the given task on the project with the given dependency set by reloading
   the project, changing the dependencies, adding middleware, initializing the
   project, and then running the task."
  [project opts dependency-set task]
  (-> project
      (reload-project)
      (change-dependencies dependency-set)
      (plugin/add-middleware)
      (project/init-project)
      (maybe-remove-dependencies opts)
      (lein/resolve-and-apply task)))
