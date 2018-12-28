(ns lein-monolith.task.fingerprint
  (:require
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [lein-monolith.dependency :as dep]
    [lein-monolith.task.util :as u]
    [multihash.core :as mhash]
    [multihash.digest :as digest]
    [puget.color.ansi :as ansi])
  (:import
    (java.io
      File
      PushbackInputStream)))


(def ^:private ->multihash
  "Globally storing the algorithm we use to generate each multihash."
  digest/sha1)


(defn- aggregate-hashes
  "Takes a collection of multihashes, and aggregates them together into a unified hash."
  [mhashes]
  ;; TODO: is there a better way to do this?
  (if (= 1 (count mhashes))
    (first mhashes)
    (->> mhashes
         (map mhash/base58)
         (sort)
         (apply str)
         (->multihash))))


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
      (->multihash in))))


(defn- hash-sources
  [project paths-key]
  (->> (paths-key project)
       (map (fn absolute-file
              [dir-str]
              ;; Monolith subprojects don't have absolute paths
              (if (str/starts-with? dir-str (:root project))
                (jio/file dir-str)
                (jio/file (:root project) dir-str))))
       (mapcat list-all-files)
       (map hash-file)
       (aggregate-hashes)))


(defn- hash-dependencies
  [project]
  (-> (:dependencies project)
      (pr-str)
      (->multihash)))


(declare project-fingerprint)


(defn- hash-upstream-projects
  [project dep-map subprojects cache]
  (->> (dep-map (dep/project-name project))
       (keep subprojects)
       (map #(project-fingerprint % dep-map subprojects cache))
       (aggregate-hashes)))


(defn- cache-result!
  [cache project m]
  (get (swap! cache assoc (dep/project-name project) m) (dep/project-name project)))


(defn all-fingerprints
  "Computes the various subfingerprints and final aggregate fingerprint for a project.

  Returns a map of `{:type-of-fingerprint <mhash>}`, with the final fingerprint
  in the `:final` key, so it's easier to explain what caused a project's
  fingerprint to change.

  Keeps a cache of fingerprints computed so far, for efficiency."
  [project dep-map subprojects cache]
  (or (@cache (dep/project-name project))
      (let [prints
            {:sources (hash-sources project :source-paths)
             :tests (hash-sources project :test-paths)
             :resources (hash-sources project :resource-paths)
             :deps (hash-dependencies project)
             :upstream (hash-upstream-projects project dep-map subprojects cache)}]
        (cache-result!
          cache project
          (assoc prints :final (aggregate-hashes (vals prints)))))))


(defn project-fingerprint
  "Returns just the final aggregate fingerprint for a project."
  [project dep-map subprojects cache]
  (:final (all-fingerprints project dep-map subprojects cache)))


(defn changed
  [project opts]
  (when (:monolith project)
    (lein/abort "Cannot (yet) run on monolith project"))
  (time
    (let [[monolith subprojects] (u/load-monolith! project)
          dep-map (dep/dependency-map subprojects)
          cache (atom {})
          prints (all-fingerprints project dep-map subprojects cache)]
      (puget.printer/cprint cache)
      (lein/info "fingerprint:" (pr-str (into {} (map (juxt key (comp mhash/base58 val))) prints))))))
