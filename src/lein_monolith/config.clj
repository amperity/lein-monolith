(ns lein-monolith.config
  (:require
    [clojure.java.io :as jio]
    [leiningen.core.main :as lein])
  (:import
    (java.io
      PushbackReader)
    (java.nio.file
      Files
      LinkOption
      Paths)))


(def config-name "monolith.clj")


(defn- read-clj
  "Reads the first data structure in a clojure file."
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
        (lein/warn "WARN:" (str project-file) "does not appear to be a valid leiningen project definition!")))))


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


(defn load!
  "Reads the monolith configuration file and returns the contained data
  structure. Aborts with an error if the file is not found."
  ([]
   (load! (System/getProperty "user.dir")))
  ([dir]
   (let [file (find-config dir)]
     (when-not file
       (lein/abort "Could not find configuration file" config-name "in any parent directory of" dir))
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


(defn internal-project?
  "Determines whether the given project symbol names a project defined inside
  the monorepo."
  [config project-name]
  (boolean (get-in config [:internal-projects project-name])))
