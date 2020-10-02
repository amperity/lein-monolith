(ns lein-monolith.test-utils
  (:require
    [clojure.test :refer [use-fixtures]]
    [lein-monolith.task.each :as each]
    [lein-monolith.task.util :as u]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [leiningen.deps :as deps]
    [leiningen.install :as install])
  (:import
    (java.io
      StringWriter)))


(defn read-example-project
  "Read in the example monolith project."
  []
  (project/read "example/project.clj"))


(defn prepare-example-project
  "Prepare the example project by installing the source version of
  lein-monolith, fetching the example project's dependencies, and installing all
  of the example project's subprojects."
  []
  (let [out (StringWriter.)]
    (try
      (binding [lein/*exit-process?* false
                *out* out
                *err* out]
        (install/install (project/read "project.clj"))
        (let [[monolith subprojects] (u/load-monolith! (read-example-project))]
          (deps/deps monolith)
          (doseq [[_project-name project] subprojects]
            (each/run-tasks project {} ["install"]))))
      (catch Exception e
        (.println *err* (str out))
        (throw e)))))


(defn use-example-project
  "Adds a fixture that ensures that the example project is completely set up so
  monolith tasks can be run against it for testing."
  []
  (use-fixtures :once (fn [f]
                        (prepare-example-project)
                        (f))))
