(ns lein-monolith.plugin
  "This namespace runs inside of Leiningen on all projects and will
  automatically activate the `with-all` task if the project map sets a truthy
  value for the `:monolith` key."
  (:require
    [clojure.java.io :as jio]
    (leiningen.core
      [main :as lein]
      [project :as project])
    [lein-monolith.config :as config]))


(defn monolith-profile
  "Constructs a profile map containing merged source and test paths."
  [config]
  (let [projects (:internal-projects config)]
    (reduce-kv
      (fn [profile project-name {:keys [dir]}]
        (lein/debug "Reading" project-name "subproject definiton from" dir)
        (let [project (project/read (str (jio/file dir "project.clj")))]
          (-> profile
              (update :source-paths concat (:source-paths project))
              (update :test-paths   concat (:test-paths   project))
              ; TODO: merge :dependencies
              )))
      {:source-paths []
       :test-paths []
       :dependencies []}
      projects)))


(defn add-profile
  "Adds the monolith profile to the given project if it's not already present."
  [project]
  (if (get-in project [:profiles :monolith/all])
    project
    (let [profile (monolith-profile (config/load!))]
      (lein/debug "Adding monolith profile to project...")
      (project/add-profiles project {:monolith/all profile}))))


(defn activate-profile
  "Activates the monolith profile in the project if it's not already active."
  [project]
  (if (contains? (set (:active-profiles (meta project))) :monolith/all)
    project
    (do (lein/debug "Merging monolith profile into project...")
        (project/merge-profiles project [:monolith/all]))))


(defn middleware
  "Automatically adds the merged monolith profile to the project if it contains
  a truthy value in `:monolith`."
  [project]
  (if (:monolith project)
    ; Monolith project, load up merged profile.
    (-> project
        (add-profile)
        (activate-profile))
    ; Normal project, don't activate.
    project))
