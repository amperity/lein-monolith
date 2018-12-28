(ns lein-monolith.task.fingerprint
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [lein-monolith.task.util :as u]
    [multihash.core :as mhash]
    [multihash.digest :as digest]
    [puget.color.ansi :as ansi])
  (:import
    (java.io
      File
      PushbackInputStream)))


(defn- aggregate
  "Takes a collection of multihashes, and aggregates them together into a unified hash."
  [mhashes]
  ;; TODO: is there a better way to do this?
  (->> mhashes
       (map mhash/base58)
       (sort)
       (apply str)
       (digest/sha1)))


(defn- list-all-files
  [^File file]
  (if (.isFile file)
    [file]
    (mapcat list-all-files (.listFiles file))))


(defn- hash-file
  "Takes a File object, and returns a multihash that uniquely identifies the
  content of this file and the location of the file."
  [^File file]
  ;; TODO: only prefix the file location relative to the subproject root?
  (let [prefix (.getBytes (str (.getAbsolutePath file) "\n"))]
    (with-open [in (PushbackInputStream. (jio/input-stream file) (count prefix))]
      (.unread in prefix)
      (digest/sha1 in))))


(defn- local-fingerprint
  [project]
  (->> (concat (:source-paths project)
               (:test-paths project)
               (:resource-paths project))
       (map jio/file)
       (mapcat list-all-files)
       (map hash-file)
       (aggregate)))


(defn- cache-fingerprint!
  [cache project f]
  (get (swap! cache assoc (:name project) f) (:name project)))


(defn fingerprint
  "Computes the fingerprint for a project, based on its local files as well as its
  dependencies' fingerprints. Keeps a cache of fingerprints computed so far, for
  efficiency."
  [cache dep-map subprojects project]
  (or (@cache (:name project))
      (let [f (local-fingerprint)])
      (cache-fingerprint!
        cache project
        (let []))))


(defn changed
  [project opts]
  (when (:monolith project)
    (lein/abort "Cannot (yet) run on monolith project"))
  (lein/info "fingerprint:" (local-fingerprint project)))
