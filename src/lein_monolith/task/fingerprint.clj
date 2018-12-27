(ns lein-monolith.task.fingerprint
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [puget.color.ansi :as ansi]))


(defn changed
  "Prints a report about the projects whose fingerprints have changed.

  Options:
    :bare       Only print the project names and directories, one per line"
  [project args]
  )


(defn fingerprint
  "Tasks for working with subproject fingerprinting."
  {:subtasks [#'report]}
  [project command & args]
  (case command
    "changed" (changed project args)
    (lein/abort (pr-str command) "is not a valid fingerprint subcommand! Try: lein help monolith fingerprint")))
