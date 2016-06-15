(ns leiningen.monolith
  "Leiningen task implementations for working with monorepos."
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :refer [pprint]]
    [clojure.set :as set]
    (leiningen.core
      [main :as lein]
      [project :as project])
    (lein-monolith
      [config :as config]
      [plugin :as plugin]
      [util :as u]))
  (:import
    (java.io
      File)
    (java.nio.file
      Files
      LinkOption
      Paths)))


(defn- collapse-project-name
  "Simplifies a dependency name symbol with identical name and namespace
  components to a symbol with just a name."
  [sym]
  (if (= (namespace sym) (name sym))
    (symbol (name sym))
    sym))


(defn- map-vals
  "Helper function to map over the values in a map and re-use the keys."
  [f m]
  (into {} (map (fn [[k v]] [k (f v)]) m)))


(defn- read-project!
  "Reads the project map from the definition in the given directory."
  [dir]
  (project/read (str (jio/file dir "project.clj"))))


(defn- topological-keys
  "Returns a sequence of the keys in the map `m`, ordered such that no key `k1`
  appearing before `k2` satisfies `(contains? (get m k1) k2)`. In other words,
  earlier keys do not 'depend on' later keys."
  [m]
  (when (seq m)
    (let [roots (apply set/difference (set (keys m)) (map set (vals m)))]
      (when (empty? roots)
        (throw (ex-info "Cannot sort the keys in the given map, cycle detected!"
                        {:input m})))
      (concat (topological-keys (apply dissoc m roots)) roots))))



;; ## Subtask Implementations

(defn ^:no-project-needed info
  "Show information about the monorepo configuration."
  []
  (let [config (config/load!)]
    (println "Config path:" (:config-path config))
    (println)
    (println "Internal projects:")
    ; TODO: puget?
    (let [prefix-len (inc (count (:mono-root config)))]
      (doseq [[pname {:keys [version dir]}] (:internal-projects config)]
        (printf "  %-40s   %s\n" (pr-str [pname version]) (subs (str dir) prefix-len))))))


(defn ^:higher-order each
  "Iterate over each subproject in the monolith and apply the given task.
  Projects are iterated in dependency order; that is, later projects may depend
  on earlier ones."
  [project task-name & args]
  (let [config (config/load!)]
    (lein/info "Applying" task-name "to" (count (:internal-projects config)) "subprojects...")
    (let [project-maps (map-vals (comp read-project! :dir)
                                 (:internal-projects config))
          dependency-edges (map-vals #(set (map first (:dependencies %)))
                                     project-maps)]
      (doseq [subproject-name (topological-keys dependency-edges)]
        (lein/info "\nApplying" task-name "to" subproject-name)
        (lein/apply-task task-name (get project-maps subproject-name) args)))))


(defn ^:higher-order with-all
  "Apply the given task with a merged set of dependencies, sources, and tests
  from all the internal projects.

  For example:

      lein monolith with-all test"
  [project task-name & args]
  (when (:monolith project)
    (lein/abort "Running 'with-all' in a monolith project is redundant!"))
  ; TODO: replace with plugin functions
  (let [config (config/load!)
        profile (plugin/monolith-profile config)]
      (lein/apply-task
        task-name
        (-> project
            (assoc-in [:profiles :monolith/all] profile)
            (project/set-profiles (conj (:active-profiles (meta project)) :monolith/all)))
        args)))


(defn link
  "Create symlinks in the checkouts directory pointing to all internal
  dependencies in the current project.

  Options:
    :force       Override any existing checkout links with conflicting names"
  [project args]
  (when (:monolith project)
    (lein/abort "The 'link' task does not need to be run for the monolith project!"))
  (let [config (config/load!)
        options (set args)
        checkouts-dir (jio/file (:root project) "checkouts")]
    (when-not (.exists checkouts-dir)
      (lein/debug "Creating checkout directory" checkouts-dir)
      (.mkdir checkouts-dir))
    (doseq [spec (:dependencies project)
            :let [dependency (collapse-project-name (first spec))]]
      (when-let [^File dep-dir (get-in config [:internal-projects dependency :dir])]
        (let [link (.toPath (jio/file checkouts-dir (.getName dep-dir)))
              target (.relativize (.toPath checkouts-dir) (.toPath dep-dir))
              create-link! #(Files/createSymbolicLink link target (make-array java.nio.file.attribute.FileAttribute 0))]
          (if (Files/exists link (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
            ; Link file exists.
            (let [actual-target (Files/readSymbolicLink link)]
              (if (and (Files/isSymbolicLink link) (= target actual-target))
                ; Link exists and points to target already.
                (lein/info "Link for" dependency "is correct")
                ; Link exists but points somewhere else.
                (if (get options "--force")
                  ; Recreate link since :force is set.
                  (do (lein/warn "Relinking" dependency "from"
                                 (str actual-target) "to" (str target))
                      (Files/delete link)
                      (create-link!))
                  ; Otherwise print a warning.
                  (lein/warn "WARN:" dependency "links to" (str actual-target)
                             "instead of" (str target)))))
            ; Link does not exist, so create it.
            (do (lein/info "Linking" dependency "to" (str target))
                (create-link!))))))))


(defn check-deps
  "Check the versions of external dependencies of the current project.

  Options:
    :unlocked    Print warnings for external dependencies with no specified version
    :strict      Exit with a failure status if any versions don't match"
  [project args]
  (let [config (config/load!)
        options (set args)
        ext-deps (->> (:external-dependencies config)
                      (map (juxt first identity))
                      (into {}))
        error-flag (atom false)]
    (doseq [[pname :as spec] (:dependencies project)]
      (let [pname' (collapse-project-name pname)
            spec' (vec (cons pname' (rest spec)))]
        (when-not (config/internal-project? config pname')
          (if-let [expected-spec (ext-deps pname')]
            (when-not (= expected-spec spec')
              (lein/warn "ERROR: External dependency" (pr-str spec') "does not match expected spec" (pr-str expected-spec))
              (when (get options ":strict")
                (reset! error-flag true)))
            (when (get options ":unlocked")
              (lein/warn "WARN: External dependency" (pr-str pname') "has no expected version defined"))))))
    (when @error-flag
      (lein/abort))))



;; ## Plugin Entry

(defn monolith
  "Tasks for working with Leiningen projects inside a monorepo."
  {:subtasks [#'info #'each #'with-all #'link #'check-deps]}
  [project command & args]
  (case command
    "debug"      (pprint (config/load!))
    "info"       (info)
    "each"       (apply each project args)
    "with-all"   (apply with-all project args)
    "check-deps" (check-deps project args)
    "link"       (link project args)
    (lein/abort (pr-str command) "is not a valid monolith command! (try \"help\")"))
  (flush))
