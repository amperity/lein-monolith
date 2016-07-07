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
      [util :as u])
    [puget.color.ansi :as ansi]
    [puget.printer :as puget]))


(defn- select-dependency
  "Given a dependency name and a collection of specs for that dependency, either
  select one for use or return nil on conflicts."
  [dep-name specs]
  (let [specs (map u/unscope-coord specs)
        default-choice (first specs)
        projects-for-specs (reduce (fn [m d]
                                     (update m d (fnil conj []) (u/dep-source d)))
                                   {} specs)]
    (if (= 1 (count (distinct specs)))
      ; Only one (unique) dependency spec declared, use it.
      default-choice
      ; Multiple versions or specs declared! Warn and use the default.
      (do
        (-> (str "WARN: Multiple dependency specs found for "
                 (u/condense-name dep-name) " in "
                 (count (distinct (map u/dep-source specs)))
                 " projects - using " (pr-str default-choice) " from "
                 (u/dep-source default-choice))
            (ansi/sgr :red)
            (lein/warn))
        (doseq [[spec projects] projects-for-specs]
          (lein/warn (format "%-50s from %s"
                             (puget/cprint-str spec)
                             (str/join " " (sort projects)))))
        (lein/warn "")
        default-choice))))


(defn- dedupe-dependencies
  "Given a vector of dependency coordinates, deduplicate and ensure there are no
  conflicting versions found."
  [dependencies]
  (let [error-flag (atom false)
        chosen-deps
        (reduce-kv
          (fn [current dep-name specs]
            (if-let [choice (select-dependency dep-name specs)]
              (conj current choice)
              (do (reset! error-flag true)
                  current)))
          []
          (group-by first dependencies))]
    (when @error-flag
      (lein/abort "Unresolvable dependency conflicts!"))
    chosen-deps))


(defn remove-internal
  "Given a vector of dependency coordinates and a collection of internal
  project names, return a vector without internal dependencies."
  [dependencies subproject-names]
  (vec (remove (comp (set subproject-names) first) dependencies)))


(defn monolith-profile
  "Constructs a profile map containing merged source and test paths."
  [subprojects]
  (->
    (reduce-kv
      (fn [profile project-name project]
        (let [dependencies (map #(u/with-source % project-name)
                                (:dependencies project))]
          (-> profile
              (update :source-paths concat (:source-paths project))
              (update :test-paths   concat (:test-paths project))
              (update :dependencies concat dependencies))))
      {:source-paths []
       :test-paths []
       :dependencies []}
      subprojects)
    (update :dependencies dedupe-dependencies)
    (update :dependencies remove-internal (keys subprojects))
    (assoc :monolith/subprojects subprojects)))


(defn add-profile
  "Adds the monolith profile to the given project if it's not already present."
  [project]
  (if (get-in project [:profiles :monolith/all])
    project
    (let [config (config/read!)
          subprojects (config/load-subprojects! config)
          profile (monolith-profile subprojects)]
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
