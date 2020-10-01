(ns lein-monolith.plugin
  "This namespace runs inside of Leiningen on all projects and handles profile
  creation for `with-all` and `inherit` functionality."
  (:require
    [lein-monolith.config :as config]
    [lein-monolith.dependency :as dep]
    [leiningen.core.eval :as eval]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]))


;; ## Profile Generation

(def profile-config
  "Configuration for inherited profiles. Structured as a vector of pairs to
  maintain ordering."
  [[:monolith/inherited
    {:types #{}
     :ks {:inherit :inherit
          :subproject :monolith/inherit}}]

   [:monolith/inherited-raw
    {:types #{:raw}
     :ks {:inherit :inherit-raw
          :subproject :monolith/inherit-raw}}]

   [:monolith/leaky
    {:types #{:leaky}
     :ks {:inherit :inherit-leaky
          :subproject :monolith/inherit-leaky}}]

   [:monolith/leaky-raw
    {:types #{:leaky :raw}
     :ks {:inherit :inherit-leaky-raw
          :subproject :monolith/inherit-leaky-raw}}]])


(defn- subproject-dependencies
  "Given a map of internal projects, return a vector of dependency coordinates
  for the subprojects."
  [subprojects]
  (mapv #(vector (key %) (:version (val %))) subprojects))


(defn- maybe-mark-leaky
  "Add ^:leaky metadata to a profile if it is of the leaky type."
  [profile types]
  (cond-> profile
    (:leaky types) (vary-meta assoc :leaky true)))


(defn- choose-inheritance-source
  "Choose either the initialized monolith or its raw representation for use when
  building an inherited profile."
  [monolith types]
  (if (:raw types)
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
                      {:inherit setting})))))


(defn- inherited-profile
  "Constructs a profile map containing the inherited properties from a parent
  project map."
  [monolith subproject {inherit-key :inherit subproject-key :subproject}]
  (when-let [base-properties (get-in monolith [:monolith inherit-key])]
    (when-let [profile (select-inherited-properties monolith base-properties subproject subproject-key)]
      (when (contains? profile :profiles)
        (lein/warn "WARN: nested profiles cannot be inherited; ignoring :profiles in monolith" inherit-key))
      (dissoc profile :profiles))))


(defn build-inherited-profiles
  "Returns a map from profile keys to inherited profile maps."
  [monolith subproject]
  (reduce
    (fn [acc [profile-key {:keys [types ks]}]]
      (let [profile (some-> (choose-inheritance-source monolith types)
                            (inherited-profile subproject ks)
                            (maybe-mark-leaky types))]
        (cond-> acc
          profile (assoc profile-key profile))))
    nil
    profile-config))


(def ^:private init-lock
  "An object to lock on to ensure that projects are not initialized
  concurrently. This prevents the mysterious 'unbound fn' errors that sometimes
  crop up during parallel execution."
  (Object.))


(declare add-middleware)


(defn init-subproject
  "Reads and fully initializes a subproject with inherited monolith profiles."
  [subproject]
  (binding [eval/*dir* (:root subproject)]
    (config/debug-profile "init-subproject"
      (locking init-lock
        (project/init-project
          (add-middleware subproject))))))


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
            (reduce (->> subproject
                         (init-subproject)
                         (partial add-profile-paths))
                    profile
                    path-keys))
          (select-keys monolith path-keys)
          subprojects)]
    (as-> profile v
          (reduce (fn sort-paths [acc k] (update acc k sort)) v path-keys)
          (assoc v :dependencies (subproject-dependencies subprojects)))))


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
    (if (some (fn this-profile-active?
                [entry]
                (profile-active? project (first entry)))
              profile-config)
      ; Already activated, return project.
      (do (lein/debug "One or more inherited profiles are already active!")
          project)
      ; Find monolith metaproject and generate profile.
      (let [monolith (config/find-monolith! project)
            profiles (build-inherited-profiles monolith project)]
        (reduce-kv add-active-profile project profiles)))
    ; Normal project, don't activate.
    project))


(defn add-middleware
  "Update the given project to include the plugin middleware. Appends the
  middleware symbol if it is not already present."
  [project]
  (let [mw-sym 'lein-monolith.plugin/middleware]
    (if (some #{mw-sym} (:middleware project))
      project
      (update project :middleware (fnil conj []) mw-sym))))
