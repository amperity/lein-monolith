(ns lein-monolith.config
  (:require
    [clojure.java.io :as jio]
    (leiningen.core
      [main :as lein]
      [project :as project])
    [lein-monolith.dependency :as dep])
  (:import
    java.io.File))


(defmacro debug-profile
  "Measure the time to compute the value in `body`, printing out a debug
  message with the total elapsed time."
  [message & body]
  `(let [start# (System/nanoTime)
         message# ~message
         value# (do ~@body)
         elapsed# (/ (- (System/nanoTime) start#) 1000000.0)]
     (lein/debug "Elapsed:" message# (format "=> %.3f ms" elapsed#))
     value#))



;; ## General Configuration

(defn- find-up
  "Searches upward from the given root directory, locating files with the given
  name. Returns a sequence of `File` objects in the order they occur in the
  parents of `root`."
  [root file-name]
  (when root
    (lazy-seq
      (let [dir (jio/file root)
            file (jio/file dir file-name)
            next-files (find-up (.getParentFile dir) file-name)]
        (if (.exists file)
          (cons file next-files)
          next-files)))))


(defn find-monolith
  "Returns the loaded project map for the monolith metaproject, or nil if not
  found.

  If the given project already has the `:monolith` key, it's returned directly.
  Otherwise, parent directories are searched using `find-up` and any projects
  are loaded to check for the `:monolith` entry."
  [project]
  (if (:monolith project)
    project
    (some (fn check-project
            [file]
            (lein/debug "Reading candidate project file" (str file))
            (let [super (project/read-raw (str file))]
              (when (:monolith super)
                super)))
          (find-up (:root project) "project.clj"))))


(defn find-monolith!
  "Returns the loaded project map for the monolith metaproject. Aborts with an
  error if not found."
  [project]
  (let [monolith (debug-profile "find-monolith" (find-monolith project))]
    (when-not monolith
      (lein/abort "Could not find monolith project in any parent directory of"
                  (:root project)))
    (lein/debug "Found monolith project rooted at" (:root monolith))
    monolith))



;; ## Subproject Configuration

(defn- pick-directories
  "Given a path, use it to find directories. If the path names a directory,
  return a vector containing it. If the path ends in `/*` and the parent is a
  directory, return a sequence of directories which are children of the parent.
  Otherwise, returns nil."
  [^File file]
  (cond
    (.isDirectory file)
      [file]
    (and (= "*" (.getName file)) (.isDirectory (.getParentFile file)))
      (->> (.getParentFile file)
           (.listFiles)
           (filter #(.isDirectory ^File %)))
    :else
      nil))


(defn- read-subproject
  "Reads a leiningen project definition from the given directory and returns
  the loaded project map, or nil if the directory does not contain a valid
  `project.clj` file."
  [dir]
  (let [project-file (jio/file dir "project.clj")]
    (when (.exists project-file)
      (lein/debug "Reading subproject definition from" (str project-file))
      (project/read-raw (str project-file)))))


(defn read-subprojects!
  "Returns a map of (condensed) project names to raw leiningen project
  definitions for all the subprojects in the repo."
  [monolith]
  (->>
    (get-in monolith [:monolith :project-dirs])
    (map (partial jio/file (:root monolith)))
    (mapcat pick-directories)
    (keep read-subproject)
    (map (juxt dep/project-name identity))
    (into {})
    (debug-profile "read-subprojects!")))



;; ## Misc Configuration

(defn get-selector
  "Looks up the subproject selector from the configuration map. Aborts if a
  selector is specified but does not exist."
  [monolith selector-key]
  (when selector-key
    (let [selectors (get-in monolith [:monolith :project-selectors])
          selector (get selectors selector-key)]
      (when-not selector
        (lein/abort (format "Project selector %s is not configured in %s"
                            selector-key (keys selectors))))
      (eval selector))))
