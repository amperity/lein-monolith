(ns leiningen.monolith
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :refer [pprint]])
  (:import
    (java.io
      File
      PushbackReader)))


(def config-name "monolith.clj")


(defn- read-clj
  "Read the first data structure in a clojure file."
  [file]
  (-> (jio/file file)
      (jio/reader)
      (PushbackReader.)
      (read)))


(defn- find-config
  "Searches upward from the project root until it finds a configuration file.
  Returns the `File` object if found, or nil if no matching file could be
  located in the parent directories."
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
     (-> (read-clj file)
         (assoc :config-path (str file))))))


(defn- mono-root
  "Returns the path to the monorepo's root."
  [config]
  (.getParent (jio/file (:config-path config))))


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
  [config]
  (when-let [root (mono-root config)]
    (->>
      (:project-dirs config)
      (mapcat
        (fn list-projects
          [path]
          (let [projects-dir (jio/file root path)]
            (->> (.listFiles projects-dir)
                 (map #(vector (read-project-coord %) %))
                 (filter first))))))))


(defn- merged-profile
  "Constructs a profile map containing merged `:src-paths` and `:test-paths` entries."
  [version options]
  {:src-paths ['...]
   :test-paths ['...]
   :dependencies ['...]})



;; ## Command Implementations

(defn- print-help
  []
  (println "Usage: lein monolith <command> [args...]")
  (println)
  (println "    config       Print some information about the current configuration")
  (println "    checkout     Set up checkout dependency links to internal projects")
  (println "    deps         Check external dependency versions against the approved list")
  (println "    with-all     Run the following commands with a merged profile of all project sources")
  (println "    help         Show this help message"))


(defn- print-config
  []
  (let [config (load-config!)]
    (println "Config path:" (:config-path config))
    (println)
    (println "Internal projects:")
    (let [prefix-len (inc (count (str (mono-root config))))]
      (doseq [[coord dir] (find-internal-projects config)]
        (printf "  %-40s -> %s\n" (pr-str coord) (subs (str dir) prefix-len))))))


(defn- link-checkouts!
  [project args]
  ; 1. Find dependencies (in all profiles?) and determine if any are internal
  ;    projects.
  ; 2. Create directory `(jio/file project-dir "checkouts")` if needed.
  ; 3. Create symlinks which don't exist pointing to the (ideally relative)
  ;    internal project paths. Warn about existing symlinks pointing to other
  ;    locations.
  ; TODO: allow `:force` option to override existing links
  (println "NYI"))


(defn- check-dependencies
  [project args]
  ; 1. Find dependencies (in all profiles?) and determine which are external
  ;    projects.
  ; 2. For each external dependency, check the version against the approved
  ;    version from the config map. Warn if it's not present in the map.
  ; TODO: allow `:strict` option to error on mismatched versions
  (println "NYI"))


(defn- apply-with-all
  [project args]
  ; ...
  (println "NYI"))



;; ## Plugin Entry

(defn monolith
  "..."
  [project & [command & args]]
  (case command
    (nil "help")
      (print-help)

    "config"
      (print-config)

    "checkouts"
      (link-checkouts! project args)

    "deps"
      (check-dependencies project args)

    "with-all"
      (apply-with-all project args)

    (do
      (println (pr-str command) "is not a valid monolith command! (try \"help\")")
      (System/exit 1)))
  (flush))
