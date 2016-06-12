(ns leiningen.monolith
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :refer [pprint]]
    [leiningen.core.project :as project]
    [leiningen.with-profile :refer [with-profile]])
  (:import
    (java.io
      File
      PushbackReader)
    (java.nio.file
      Files
      LinkOption
      Paths)))


(def config-name "monolith.clj")


(defn- collapse-project-name
  "Simplifies a dependency name symbol with identical name and namespace
  components to a symbol with just a name."
  [sym]
  (if (= (namespace sym) (name sym))
    (symbol (name sym))
    sym))


(defn- read-clj
  "Read the first data structure in a clojure file."
  [file]
  (-> (jio/file file)
      (jio/reader)
      (PushbackReader.)
      (read)))


(defn- read-project-coord
  "Reads a leiningen project definition from the given directory and returns a
  vector of the project's name symbol and version. Returns nil if the project
  file does not exist or is invalid."
  [dir]
  (let [project-file (jio/file dir "project.clj")]
    (when-let [project (and (.exists project-file) (read-clj project-file))]
      (if (and (list? project) (= 'defproject (first project)))
        [(nth project 1) (nth project 2)]
        (println "WARN:" (str project-file) "does not appear to be a valid leiningen project definition!")))))


(defn- find-internal-projects
  "Returns a sequence of vectors containing the project name and the path to
  the project's directory."
  [root project-dirs]
  (when root
    (->>
      project-dirs
      (mapcat
        (fn list-projects
          [path]
          (let [projects-dir (jio/file root path)]
            (->> (.listFiles projects-dir)
                 (map #(vector (read-project-coord %) %))
                 (filter first))))))))


(defn- find-config
  "Searches upward from the project root until it finds a configuration file.
  Returns the `File` object if found, or nil if no matching file could be
  located in the parent directories."
  ^java.io.File
  [dir]
  (when dir
    (let [dir (jio/file dir)
          file (jio/file dir config-name)]
      (if (.exists file)
        file
        (recur (.getParent dir))))))


(defn- load-config!
  "Reads the monolith configuration file and returns the contained data
  structure."
  ([]
   (load-config! (System/getProperty "user.dir")))
  ([dir]
   (let [file (find-config dir)]
     (when-not file
       (println "Could not find configuration file" config-name "in any parent directory of" dir)
       (System/exit 1))
     (let [root (.getParent file)
           config (read-clj file)
           projects (->> (find-internal-projects root (:project-dirs config))
                         (map (fn [[[pname version] dir]]
                                [pname {:version version, :dir dir}]))
                         (into {}))]
       (assoc config
              :config-path (str file)
              :mono-root (str root)
              :internal-projects projects)))))


(defn- internal-project?
  "Determines whether the given project symbol names a project defined inside
  the monorepo."
  [config project]
  (boolean (get-in config [:internal-projects project])))


(defn- merged-profile
  "Constructs a profile map containing merged source and test paths."
  [config]
  (let [projects (:internal-projects config)]
    (reduce-kv
      (fn [profile dependency {:keys [dir]}]
        (let [project (project/read (str (jio/file dir "project.clj")))]
          (-> profile
              (update :source-paths concat (:source-paths project))
              (update :test-paths   concat (:test-paths   project))
              ; TODO: dependencies?
              )))
      {:source-paths []
       :test-paths []
       :dependencies []}
      projects)))



;; ## Command Implementations

(defn- print-help
  []
  (println "Usage: lein monolith <command> [args...]")
  (println)
  (println "    info         Print some information about the current configuration")
  (println "    checkout     Set up checkout dependency links to internal projects")
  (println "    deps         Check external dependency versions against the approved list")
  (println "    with-all     Run the following commands with a merged profile of all project sources")
  (println "    help         Show this help message"))


(defn- print-info
  "Prints some information about the monorepo configuration."
  []
  (let [config (load-config!)]
    (println "Config path:" (:config-path config))
    (println)
    (println "Internal projects:")
    (let [prefix-len (inc (count (:mono-root config)))]
      (doseq [[pname {:keys [version dir]}] (:internal-projects config)]
        (printf "  %-40s -> %s\n" (pr-str [pname version]) (subs (str dir) prefix-len))))))


(defn- link-checkouts!
  [project args]
  (let [config (load-config!)
        options (set (map read-string args))
        checkouts-dir (jio/file (:root project) "checkouts")]
    (when-not (.exists checkouts-dir)
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
                (println "Link for" dependency "is correct")
                ; Link exists but points somewhere else.
                (if (:force options)
                  ; Recreate link since :force is set.
                  (do (println "Relinking" dependency "from"
                               (str actual-target) "to" (str target))
                      (Files/delete link)
                      (create-link!))
                  ; Otherwise print a warning.
                  (println "WARN:" dependency "links to" (str actual-target)
                           "instead of" (str target)))))
            ; Link does not exist, so create it.
            (do (println "Linking" dependency "to" (str target))
                (create-link!))))))))


(defn- check-dependencies
  [project args]
  (let [config (load-config!)
        options (set (map read-string args))
        ext-deps (->> (:external-dependencies config)
                      (map (juxt first identity))
                      (into {}))
        error-flag (atom false)]
    (doseq [[pname :as spec] (:dependencies project)]
      (let [pname' (collapse-project-name pname)
            spec' (vec (cons pname' (rest spec)))]
        (when-not (internal-project? config pname')
          (if-let [expected-spec (ext-deps pname')]
            (when-not (= expected-spec spec')
              (println "ERROR: External dependency" (pr-str spec') "does not match expected spec" (pr-str expected-spec))
              (when (:strict options)
                (reset! error-flag true)))
            (when (:warn-unspecified options)
              (println "WARN: External dependency" (pr-str pname') "has no expected version defined"))))))
    (when @error-flag
      (System/exit 1))))


(defn- apply-with-all
  [project args]
  (let [config (load-config!)
        profile (merged-profile config)]
    ;(pprint profile)
    (apply with-profile
      (project/add-profiles project {:monolith/all profile})
      "monolith/all"
      args)))



;; ## Plugin Entry

(defn monolith
  "..."
  [project & [command & args]]
  (case command
    (nil "help")
      (print-help)

    "debug"
      (pprint (load-config!))

    "info"
      (print-info)

    "checkout"
      (link-checkouts! project args)

    "deps"
      (check-dependencies project args)

    "with-all"
      (apply-with-all project args)

    (do
      (println (pr-str command) "is not a valid monolith command! (try \"help\")")
      (System/exit 1)))
  (flush))
