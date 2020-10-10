(ns lein-monolith.plugin
  "This namespace runs inside of Leiningen on all projects and handles profile
  creation for `with-all` and `inherit` functionality."
  (:require
    [lein-monolith.config :as config]
    [lein-monolith.dependency :as dep]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]))


;; ## Profile Generation

(def profile-config
  "Configuration for inherited profiles. Structured as a vector of pairs to
  maintain ordering. The ordering is significant as the info command consumes
  this configuration directly, and providing deterministic output ordering is
  desirable."
  [[:monolith/inherited
    {:inherit-key :inherit
     :subproject-key :monolith/inherit}]

   [:monolith/inherited-raw
    {:raw? true
     :inherit-key :inherit-raw
     :subproject-key :monolith/inherit-raw}]

   [:monolith/leaky
    {:leaky? true
     :inherit-key :inherit-leaky
     :subproject-key :monolith/inherit-leaky}]

   [:monolith/leaky-raw
    {:leaky? true
     :raw? true
     :inherit-key :inherit-leaky-raw
     :subproject-key :monolith/inherit-leaky-raw}]])


(defn- subproject-dependencies
  "Given a map of internal projects, return a vector of dependency coordinates
  for the subprojects."
  [subprojects]
  (mapv #(vector (key %) (:version (val %))) subprojects))


(defn- maybe-mark-leaky
  "Add ^:leaky metadata to a profile if it is of the leaky type."
  [profile {:keys [leaky?]}]
  (if leaky?
    (vary-meta profile assoc :leaky true)
    profile))


(defn- choose-inheritance-source
  "Choose either the initialized monolith or its raw representation for use when
  building an inherited profile."
  [monolith {:keys [raw?]}]
  (if raw?
    (:monolith/raw (meta monolith))
    monolith))


(defn- select-inherited-properties
  "Constructs a profile map containing the inherited properties from a parent
  project map."
  [monolith base-properties subproject subproject-key]
  (let [default (boolean (:monolith/inherit subproject))
        setting (subproject-key subproject default)]
    (cond
      ;; Don't inherit anything
      (not setting)
      nil

      ;; Inherit the base properties specified in the parent.
      (true? setting)
      (select-keys monolith base-properties)

      ;; Provide additional properties to inherit, or replace if metadata is set.
      (vector? setting)
      (select-keys monolith
                   (if (:replace (meta setting))
                     setting
                     (distinct (concat base-properties setting))))

      :else
      (throw (ex-info (str "Unknown value type for monolith inherit setting: "
                           (pr-str setting))
                      {:inherit setting
                       :subproject-key subproject-key})))))


(defn- inherited-profile
  "Constructs a profile map containing the inherited properties from a parent
  project map."
  [monolith subproject {:keys [inherit-key subproject-key]}]
  (when-let [base-properties (get-in monolith [:monolith inherit-key])]
    (when-let [profile (select-inherited-properties monolith base-properties subproject subproject-key)]
      (when (contains? profile :profiles)
        (lein/warn "WARN: nested profiles cannot be inherited; ignoring :profiles in monolith" inherit-key))
      (dissoc profile :profiles))))


(defn build-inherited-profiles
  "Returns a map from profile keys to inherited profile maps."
  [monolith subproject]
  (reduce
    (fn [acc [key config]]
      (let [profile (some-> (choose-inheritance-source monolith config)
                            (inherited-profile subproject config)
                            (maybe-mark-leaky config))]
        (if profile
          (assoc acc key profile)
          acc)))
    nil
    profile-config))


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
  ([project]
   (middleware project nil))
  ([project monolith]
   (if (or (not (:monolith/inherit project)) (:monolith/active (meta project)))
     ;; Normal project or already activated monolith subproject, don't activate.
     project
     ;; Monolith subproject has not yet been activated, add inherited profile.
     (if (some (fn this-profile-active?
                 [entry]
                 (profile-active? project (first entry)))
               profile-config)
       ;; One or more profiles already present, return project.
       (do (lein/debug "One or more inherited profiles are already active!")
           project)
       ;; Find monolith metaproject and generate profile.
       (let [monolith (or monolith (config/find-monolith! project))
             profiles (build-inherited-profiles monolith project)
             with-profiles (reduce-kv add-active-profile project profiles)]
         (vary-meta with-profiles assoc :monolith/active true))))))


(defn- add-middleware
  "Update the given project to include the plugin middleware. Appends the
  middleware symbol if it is not already present."
  [subproject]
  (let [mw-sym 'lein-monolith.plugin/middleware]
    (if (some #{mw-sym} (:middleware subproject))
      subproject
      (update subproject :middleware (fnil conj []) mw-sym))))


(defn apply-middleware
  "Update the given project to include the plugin middleware. Appends the
  middleware symbol if it is not already present, then applies the middleware
  with the given monolith."
  [subproject monolith]
  (middleware (add-middleware subproject) monolith))


;; ## Merged Profiles (`with-all`) Creation


(def ^:private path-keys
  "Project map keys for (re)source and test paths."
  #{:resource-paths :source-paths :test-paths})


(defn- add-profile-paths
  "Update a profile paths entry by adding the paths from the given project.
  Returns the updated profile."
  [project profile k]
  (update profile k (fn combine-colls
                      [coll]
                      (-> coll
                          set
                          (into (get project k))
                          (vary-meta assoc :replace true)))))


(defn merged-profile
  "Constructs a profile map containing merged (re)source and test paths."
  [monolith subprojects]
  (let [profile
        (reduce-kv
          (fn [profile _project-name subproject]
            (let [with-inherited-profiles (middleware subproject monolith)
                  project (project/absolutize-paths with-inherited-profiles)]
              (reduce (partial add-profile-paths project)
                      profile
                      path-keys)))
          (select-keys monolith path-keys)
          subprojects)]
    (as-> profile v
          (reduce (fn sort-paths [acc k] (update acc k sort)) v path-keys)
          (assoc v :dependencies (subproject-dependencies subprojects)))))
