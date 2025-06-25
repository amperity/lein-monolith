(ns lein-monolith.task.coverage
  (:require
    [lein-monolith.task.coverage :as coverage]
    [leiningen.cloverage :as cloverage]))


(defn run-task
  [project & _]
  (cloverage/cloverage project "-o" (str "/tmp/coverage/" (:name project))))
