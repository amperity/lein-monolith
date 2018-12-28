(ns lein-monolith.task.fingerprint
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [puget.color.ansi :as ansi]))


(defn changed
  [project opts]
  (lein/info opts))
