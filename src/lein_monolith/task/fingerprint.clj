(ns lein-monolith.task.fingerprint
  (:require
    [clojure.data]
    [clojure.edn :as edn]
    [clojure.java.io :as jio]
    [clojure.set :as set]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [lein-monolith.dependency :as dep]
    [lein-monolith.target :as target]
    [lein-monolith.task.util :as u]
    [multihash.core :as mhash]
    [multihash.digest :as digest]
    [puget.color.ansi :as ansi])
  (:import
    (java.io
      File
      PushbackInputStream)))


(def changed-opts
  (merge
    target/selection-opts
    {:upstream 0
     :downstream 0}))


;; ## Generating fingerprints

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


(declare fingerprint-info)


(defn- hash-upstream-projects
  [project dep-map subprojects cache]
  (->> (dep-map (dep/project-name project))
       (keep subprojects)
       (map #(::final (fingerprint-info % dep-map subprojects cache)))
       (aggregate-hashes)))


(defn- cache-result!
  [cache project m]
  (get (swap! cache assoc (dep/project-name project) m) (dep/project-name project)))


(defn- hash-inputs
  "Hashes each of a project's inputs, and returns a map containing each individual
  result, so it's easier to explain what aspect of a project caused its overall
  fingerprint to change.

  Returns a map of `{::xyz <mhash>}`

  Keeps a cache of hashes computed so far, for efficiency."
  [project dep-map subprojects cache]
  (or (@cache (dep/project-name project))
      (let [prints
            {::sources (hash-sources project :source-paths)
             ::tests (hash-sources project :test-paths)
             ::resources (hash-sources project :resource-paths)
             ::deps (hash-dependencies project)
             ::upstream (hash-upstream-projects project dep-map subprojects cache)}]
        (cache-result! cache project prints))))


(defn fingerprint-info
  "Returns a map of fingerpint info for a project, that can be compared with a
  previous fingerprint file."
  [project dep-map subprojects cache]
  (let [prints (hash-inputs project dep-map subprojects cache)]
    (assoc prints
           ::final (aggregate-hashes (vals prints))
           ::time (System/currentTimeMillis))))


;; ## Storing fingerprints

;; The .lein-monolith-fingerprints file at the metaproject root stores the
;; detailed fingerprint map for each project and marker type.

(comment
  ;; Example .lein-monolith-fingerprints
  {:build {foo/bar {::sources "multihash abcde"
                    ::tests "multihash fghij"
                    ,,,
                    ::final "multihash vwxyz"}
           ,,,}
   ,,,})


(defn- fingerprints-file
  ^File
  [monolith]
  (jio/file (str (:root monolith) ".lein-monolith-fingerprints")))


(defn- read-fingerprints
  [monolith]
  (let [f (fingerprints-file monolith)]
    (when (.exists f)
      (edn/read-string (slurp f)))))


(defn- write-fingerprints!
  [monolith fingerprints]
  (let [f (fingerprints-file monolith)]
    (spit f (pr-str fingerprints))))


;; ## Comparing fingerprints

(defn- changed-projects
  "Takes two detailed fingerprint maps, and returns a set of project names that
  have a current fingerprint but changed."
  [past current]
  (into #{}
        (keep
          (fn compare-fingerprints
            [[project-name current-info]]
            (let [past-info (get past project-name)]
              (when (not= (::final past-info) (::final current-info))
                project-name))))
        current))


(defn- explain-change
  [past current project-name]
  (let [past-info (get past project-name)
        current-info (get current project-name)]
    (println project-name "changed:")
    (prn (take 2 (clojure.data/diff past-info current-info)))))


(defn changed
  [project opts]
  (let [[monolith subprojects] (u/load-monolith! project)
        dep-map (dep/dependency-map subprojects)
        project-name (dep/project-name project)
        opts' (cond-> opts
                (:upstream opts)
                (update :upstream-of conj (str project-name))

                (:downstream opts)
                (update :downstream-of conj (str project-name)))
        targets (target/select monolith subprojects opts')
        cache (atom {})
        current (->> targets
                     (keep
                       (fn [project-name]
                         (when-let [subproject (subprojects project-name)]
                           [project-name
                            (fingerprint-info
                              subproject dep-map subprojects cache)])))
                     (into {}))
        past (read-fingerprints monolith)
        changed (changed-projects past current)]
    (doseq [project-name changed]
      (explain-change past current project-name))))
