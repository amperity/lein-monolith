(ns lein-monolith.config
  (:require
    [clojure.java.io :as jio]
    (leiningen.core
      [main :as lein]
      [project :as project])
    [lein-monolith.dependency :as dep])
  (:import
    java.io.File))


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
            (lein/debug "Reading parent project file" (str file))
            (let [super (project/read (str file))]
              (when (:monolith super)
                super)))
          (find-up (:root project) "project.clj"))))


(defn find-monolith!
  "Returns the loaded project map for the monolith metaproject. Aborts with an
  error if not found."
  [project]
  (let [monolith (find-monolith project)]
    (when-not monolith
      (lein/abort "Could not find monolith project in any parent directory of"
                  (:root project)))
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
  [project]
  (->>
    (get-in project [:monolith :project-dirs])
    (map (partial jio/file (:root project)))
    (mapcat pick-directories)
    (keep read-subproject)
    (map (juxt dep/project-name identity))
    (into {})))
