(ns lein-monolith.plugin
  "This namespace runs inside of Leiningen on all projects and will
  automatically activate the `with-all` task if the project map sets a truthy
  value for the `:monolith` key."
  (:require
    [clojure.java.io :as jio]
    [leiningen.core.project :as project]
    [lein-monolith.config :as config]))


(defn monolith-profile
  "Constructs a profile map containing merged source and test paths."
  [config]
  (let [projects (:internal-projects config)]
    (reduce-kv
      (fn [profile project-name {:keys [dir]}]
        (let [project (project/read (str (jio/file dir "project.clj")))]
          (-> profile
              (update :source-paths concat (:source-paths project))
              (update :test-paths   concat (:test-paths   project))
              ; TODO: merge :dependencies
              ; TODO: set :target-path
              )))
      {:source-paths []
       :test-paths []
       :dependencies []}
      projects)))


(defn middleware
  [project]
  (if (:monolith project)
    ; Monolith project, load up merged profile.
    (let [config (config/load!)
          profile (monolith-profile config)]
      (println "Merging monolith profile into project")
      #_
      (-> project
          (project/add-profiles {:monolith/all profile})
          (project/merge-profiles [:monolith/all]))
      (project/merge-profiles project [profile]))
    ; Normal project, don't activate.
    project))
