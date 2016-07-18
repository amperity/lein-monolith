(ns lein-monolith.plugin
  "This namespace runs inside of Leiningen on all projects and will
  automatically activate the `with-all` task if the project map sets a truthy
  value for the `:monolith` key."
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    (leiningen.core
      [main :as lein]
      [project :as project])
    (lein-monolith
      [config :as config]
      [dependency :as dep])
    [puget.color.ansi :as ansi]
    [puget.printer :as puget]))


(defn- subproject-dependencies
  "Given a map of internal projects, return a vector of dependency coordinates
  for the subprojects."
  [subprojects]
  (mapv #(vector (key %) (:version (val %))) subprojects))


(defn inherited-profile
  "Constructs a profile map containing the inherited properties from a parent
  project map."
  [parent inherit]
  (let [base-properties (get-in parent [:monolith :inherit])]
    (cond
      ; Don't inherit anything
      (not inherit)
        {}

      ; Inherit the base properties specified in the parent.
      (true? inherit)
        ; TODO: instead of select-keys, could do reducing get-in/assoc-in
        (select-keys parent base-properties)

      ; Provide additional properties to inherit, or replace if metadata is set.
      (vector? inherit)
        (->> (if (:replace (meta inherit))
               inherit
               (distinct (concat base-properties inherit)))
             (select-keys parent))

      :else
        (throw (ex-info "Unknown value type for monolith inherit setting"
                        {:inherit inherit})))))


(defn merged-profile
  "Constructs a profile map containing merged (re)source and test paths."
  [subprojects]
  (->
    (reduce-kv
      (fn [profile project-name project]
        (let [dependencies (map #(dep/with-source % project-name)
                                (:dependencies project))]
          (-> profile
              (update :resource-paths concat (:resource-paths project))
              (update :source-paths   concat (:source-paths project))
              (update :test-paths     concat (:test-paths project)))))
      {:resource-paths []
       :source-paths []
       :test-paths []}
      subprojects)
    (assoc :dependencies (subproject-dependencies subprojects))
    #_(update :dependencies dep/dedupe-dependencies)))


(defn add-profile
  "Adds the monolith profile to the given project if it's not already present."
  [project profile-key profile]
  (if (= profile (get-in project [:profiles profile-key]))
    project
    (do (lein/debug "Adding" profile-key "profile to project" (dep/project-name project))
        (project/add-profiles project {profile-key profile}))))


(defn activate-profile
  "Activates the monolith profile in the project if it's not already active."
  [project profile-key]
  (if (contains? (set (:active-profiles (meta project))) profile-key)
    project
    (do (lein/debug "Merging" profile-key "profile into project" (dep/project-name project))
        (project/merge-profiles project [profile-key]))))


(defn middleware
  "Handles inherited properties in monolith subprojects by looking for the
  `:monolith/inherit` key."
  [project]
  (if (:monolith/inherit project)
    ; Monolith subproject, add inherited profile.
    (if (get-in project [:profiles :monolith/inherited])
      ; Already added the profile.
      project
      ; Generate and merge in the profile.
      (let [metaproject (config/find-monolith!)
            profile (inherited-profile metaproject (:monolith/inherit project))]
        (-> project
            (add-profile :monolith/inherited profile)
            (activate-profile :monolith/inherited))))
    ; Normal project, don't activate.
    project))
