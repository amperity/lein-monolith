(ns lein-monolith.plugin
  "This namespace runs inside of Leiningen on all projects and handles profile
  creation for `with-all` and `inherit` functionality."
  (:require
    [clojure.java.io :as io]
    [lein-monolith.config :as config]
    [lein-monolith.dependency :as dep]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]))


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
  (reduce-kv
    (fn [profile _ project]
      (-> profile
          (add-profile-paths project :resource-paths)
          (add-profile-paths project :source-paths)
          (add-profile-paths project :test-paths)))
    {:dependencies (subproject-dependencies subprojects)
     :resource-paths []
     :source-paths []
     :test-paths []}
    subprojects))


(defn- with-meta*
  "Returns an object of the same type and value as obj, with map m as its
  metadata if the object can hold metadata."
  [obj m]
  (if (instance? clojure.lang.IObj obj)
    (with-meta obj m)
    obj))


(defn- string->path
  [s]
  (-> s io/file (.toPath)))


(defn- relativize
  [root path]
  (let [root-path (string->path root)
        path-path (string->path path)]
    (str (if (.startsWith path-path root-path)
           (.relativize root-path path-path)
           path))))


(defn- relativize-path
  [{:keys [root] :as project} key]
  (cond (re-find #"-path$" (name key))
        (update project key (partial relativize root))

        (re-find #"-paths$" (name key))
        (update project key #(with-meta* (map (partial relativize root) %)
                               (meta %)))

        :else project))


(defn- relativize-paths
  [project]
  (reduce relativize-path project (keys project)))


(defn- select-properties
  [monolith properties]
  (select-keys (relativize-paths monolith) properties))


(defn inherited-profile
  "Constructs a profile map containing the inherited properties from a parent
  project map."
  [monolith inherit-key setting]
  (when-let [base-properties (get-in monolith [:monolith inherit-key])]
    (cond
      ; Don't inherit anything
      (not setting)
      nil

      ; Inherit the base properties specified in the parent.
      (true? setting)
      (select-properties monolith base-properties)

      ; Provide additional properties to inherit, or replace if metadata is set.
      (vector? setting)
      (->> (if (:replace (meta setting))
             setting
             (distinct (concat base-properties setting)))
           (select-properties monolith))

      :else
      (throw (ex-info (str "Unknown value type for monolith inherit setting: "
                           (pr-str setting))
                      {:inherit setting})))))


(defn build-inherited-profiles
  "Returns a map from profile keys to inherited profile maps."
  [monolith subproject]
  (let [inherit-profile (inherited-profile
                          monolith :inherit
                          (:monolith/inherit subproject))
        leaky-profile (inherited-profile
                        monolith :inherit-leaky
                        (:monolith/leaky subproject (boolean (:monolith/inherit subproject))))]
    (cond-> nil
      inherit-profile (assoc :monolith/inherited inherit-profile)
      leaky-profile (assoc :monolith/leaky (vary-meta leaky-profile assoc :leaky true)))))



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
    (if (or (profile-active? project :monolith/inherited)
            (profile-active? project :monolith/leaky))
      ; Already activated, return project.
      (do (lein/debug "One or both inherited profiles are already active!")
          project)
      ; Find monolith metaproject and generate profile.
      (let [monolith (config/find-monolith! project)
            profiles (build-inherited-profiles monolith project)]
        (reduce-kv add-active-profile project profiles)))
    ; Normal project, don't activate.
    project))
