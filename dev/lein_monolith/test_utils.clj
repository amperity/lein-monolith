(ns lein-monolith.test-utils
  (:require
    [clojure.test :refer [use-fixtures]]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [leiningen.deps :as deps]
    [leiningen.install :as install]))


(defn read-example-project
  "Read in the example monolith project."
  []
  (project/read "example/project.clj"))


(defn prepare-example-project
  "Prepare the example project by first installing the source version of
  lein-monolith and then fetching the example project's dependencies."
  []
  (binding [lein/*exit-process?* false]
    (install/install (project/read "project.clj"))
    (deps/deps (read-example-project))))


(defn use-example-project
  "Adds a fixture that ensures that the example project is completely set up so
  monolith tasks can be run against it for testing."
  []
  (use-fixtures :once (fn [f]
                        (with-out-str (prepare-example-project))
                        (f))))
