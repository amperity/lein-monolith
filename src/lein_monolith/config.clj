(ns lein-monolith.config
  (:require
    [clojure.java.io :as jio]
    (leiningen.core
      [main :as lein]
      [project :as project])
    [lein-monolith.util :as u])
  (:import
    (java.io
      PushbackReader)
    (java.nio.file
      Files
      LinkOption
      Paths)))


;; ## General Configuration

(def config-name "monolith.clj")


(defn- read-clj
  "Reads the first data structure in a clojure file."
  [file]
  (-> (jio/file file)
      (jio/reader)
      (PushbackReader.)
      (read)))


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


(defn read!
  "Reads the monolith configuration file and returns the contained data
  structure. Aborts with an error if the file is not found.

  Note that this function does *not* load the subproject definitions."
  ([]
   (read! (System/getProperty "user.dir")))
  ([dir]
   (let [file (find-config dir)]
     (when-not file
       (lein/abort "Could not find configuration file" config-name "in any parent directory of" dir))
     (assoc (read-clj file)
            :config-path (str file)
            :mono-root (str (.getParent file))))))



;; ## Subproject Configuration

(defn- read-project!
  "Reads a leiningen project definition from the given directory and returns
  the loaded project map, or nil if the directory does not contain a valid
  `project.clj` file."
  [dir]
  (let [project-file (jio/file dir "project.clj")]
    (when (.exists project-file)
      (lein/debug "Reading subproject definiton from" (str project-file))
      (project/read (str project-file)))))


(defn load-subprojects!
  "Returns a map of (condensed) project names to loaded leiningen project
  definitions for all the subprojects in the repo."
  [config]
  (->>
    (:project-dirs config)
    (mapcat
      (fn list-projects
        [path]
        (->> (jio/file (:mono-root config) path)
             (.listFiles)
             (keep read-project!)
             (map (juxt u/project-name identity)))))
    (into {})))
