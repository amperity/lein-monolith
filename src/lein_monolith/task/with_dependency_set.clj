(ns lein-monolith.task.with-dependency-set
  (:require
    [clojure.java.io :as io]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.task.each :as each]
    [lein-monolith.task.util :as u]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]))


(defn- maybe-remove-dependencies
  [project opts]
  (if (:only opts)
    (assoc project :dependencies '())
    project))


(defn- reload-project
  [project]
  (-> project
      (:root)
      (io/file "project.clj")
      (str)
      (project/read-raw)))


(defn- re-init-project
  [project update-fn opts]
  (-> project
      (reload-project)
      (update-fn)
      (maybe-remove-dependencies opts)
      (each/init-project)))


(defn run-task
  [project opts dependency-set task]
  (let [[monolith _] (u/load-monolith! project)
        dependencies (plugin/get-dependencies-from-set! monolith dependency-set project)
        update-fn (if (:monolith project)
                    #(assoc % :managed-dependencies dependencies)
                    #(assoc % :monolith/dependency-set dependency-set))
        project (re-init-project project update-fn opts)]
    (lein/resolve-and-apply project task)))
