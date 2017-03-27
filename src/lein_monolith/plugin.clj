(ns lein-monolith.plugin
  "This namespace runs inside of Leiningen on all projects and handles profile
  creation for `with-all` and `inherit` functionality."
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


(def default-injections
  "Set of default properties which, if inherited, are injected directly into
  projects rather than being pulled in via a profile."
  #{:repositories
    :managed-dependencies})



;; ## Profile Generation

(defn- subproject-dependencies
  "Given a map of internal projects, return a vector of dependency coordinates
  for the subprojects."
  [subprojects]
  (mapv #(vector (key %) (:version (val %))) subprojects))


(defn- add-profile-paths
  "Update a profile paths entry by adding the absolute paths from the given
  project. Returns the updated profile."
  [profile project k]
  (update profile k into
          (map (partial str (:root project) "/")
               (get project k))))


(defn merged-profile
  "Constructs a profile map containing merged (re)source and test paths."
  [subprojects]
  (let [add-paths (fn update-paths
                    [profile project k]
                    (update profile k concat
                            (map (partial str (:root project) "/")
                                 (get project k))))]
    (->
      (reduce-kv
        (fn [profile project-name project]
          (-> profile
              (add-profile-paths project :resource-paths)
              (add-profile-paths project :source-paths)
              (add-profile-paths project :test-paths)))
        {:dependencies (subproject-dependencies subprojects)
         :resource-paths []
         :source-paths []
         :test-paths []}
        subprojects))))


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


(defn inherited-injections
  "Constructs a map of project attributes which should be directly injected
  into the subproject config."
  [parent inherited]
  (select-keys inherited
               (get-in parent
                       [:monolith :inherit/inject]
                       default-injections)))



;; ## Profile Utilities

(defn profile-active?
  "Check whether the given profile key is in the set of active profiles on the
  given project."
  [project profile-key]
  (contains? (set (:active-profiles (meta project))) profile-key))


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
  (if (profile-active? project profile-key)
    project
    (do (lein/debug "Merging" profile-key "profile into project" (dep/project-name project))
        (project/merge-profiles project [profile-key]))))


(defn add-active-profile
  "Combines the effects of `add-profile` and `activate-profile`."
  [project profile-key profile]
  (-> project
      (add-profile profile-key profile)
      (activate-profile profile-key)))



;; ## Plugin Middleware

(defn middleware
  "Handles inherited properties in monolith subprojects by looking for the
  `:monolith/inherit` key."
  [project]
  (if (:monolith/inherit project)
    ; Monolith subproject, add inherited profile.
    (if (profile-active? project :monolith/inherited)
      ; Already active, return project.
      project
      ; Find monolith metaproject and generate profile.
      (let [metaproject (config/find-monolith! project)
            profile (inherited-profile metaproject (:monolith/inherit project))]
        (-> project
            (merge (inherited-injections metaproject profile))
            (add-active-profile :monolith/inherited profile))))
    ; Normal project, don't activate.
    project))
