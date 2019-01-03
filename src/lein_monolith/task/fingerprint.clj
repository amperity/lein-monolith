(ns lein-monolith.task.fingerprint
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [leiningen.core.main :as lein]
    [leiningen.core.project :as project]
    [lein-monolith.dependency :as dep]
    [lein-monolith.target :as target]
    [lein-monolith.task.util :as u]
    [multihash.core :as mhash]
    [multihash.digest :as digest]
    [puget.color.ansi :as ansi]
    [puget.printer :as puget])
  (:import
    (java.io
      File
      PushbackInputStream)))


;; ## Options

(def selection-opts
  (assoc target/selection-opts :upstream 0 :downstream 0))


;; ## Hashing projects' inputs

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
    (with-open [in (PushbackInputStream. (io/input-stream file) (count prefix))]
      (.unread in prefix)
      (->multihash in))))


(defn- all-paths
  "Finds all source, test, and resource paths associated with a project, including
  those set in profiles."
  [project]
  (->> (concat
         [project]
         (vals (:profiles project)))
       (mapcat (juxt :source-paths :test-paths :resource-paths))
       (mapcat identity)
       (map (fn absolute-file
              [dir-str]
              ;; Monolith subprojects and profiles don't use absolute paths
              (if (str/starts-with? dir-str (:root project))
                (io/file dir-str)
                (io/file (:root project) dir-str))))))


(defn- hash-sources
  [project]
  (->> (all-paths project)
       (mapcat list-all-files)
       (map hash-file)
       (aggregate-hashes)))


(defn- hash-dependencies
  [project]
  (-> (:dependencies project)
      (pr-str)
      (->multihash)))


(declare hash-inputs)


(defn- hash-upstream-projects
  [project dep-map subprojects cache]
  (->> (dep-map (dep/project-name project))
       (keep subprojects)
       (map #(::final (hash-inputs % dep-map subprojects cache)))
       (aggregate-hashes)))


(defn- cache-result!
  [cache project m]
  (swap! cache assoc (dep/project-name project) m)
  m)


(defn- hash-inputs
  "Hashes each of a project's inputs, and returns a map containing each individual
  result, so it's easier to explain what aspect of a project caused its overall
  fingerprint to change.

  Returns a map of `{::xyz <mhash>}`

  Keeps a cache of hashes computed so far, for efficiency."
  [project dep-map subprojects cache]
  (or (@cache (dep/project-name project))
      (let [prints
            {::sources (hash-sources project)
             ::deps (hash-dependencies project)
             ::upstream (hash-upstream-projects project dep-map subprojects cache)}

            prints
            (assoc prints
                   ::final (aggregate-hashes (vals prints))
                   ::time (System/currentTimeMillis))]
        (cache-result! cache project prints))))


;; ## Storing fingerprints

;; The .lein-monolith-fingerprints file at the metaproject root stores the
;; detailed fingerprint map for each project and marker type.

(comment
  ;; Example .lein-monolith-fingerprints
  {:build {foo/bar {::sources "multihash abcde"
                    ,,,
                    ::final "multihash vwxyz"}
           ,,,}
   ,,,})


(defn- fingerprints-file
  ^File
  [monolith]
  (io/file (:root monolith) ".lein-monolith-fingerprints"))


(defn- read-fingerprints-file
  [monolith]
  (let [f (fingerprints-file monolith)]
    (when (.exists f)
      (edn/read-string
        {:readers {'data/hash mhash/decode}}
        (slurp f)))))


(defn- write-fingerprints-file!
  [monolith fingerprints]
  (let [f (fingerprints-file monolith)]
    (spit f (puget/pprint-str
              fingerprints
              {:print-handlers
               {multihash.core.Multihash
                (puget/tagged-handler 'data/hash mhash/base58)}}))))


(let [lock (Object.)]
  (defn update-fingerprints-file!
    [monolith f & args]
    (locking lock
      (write-fingerprints-file!
        monolith
        (apply f (read-fingerprints-file monolith) args)))))


;; ## Generating and comparing fingerprints

(defn context
  "Create a stateful context to use for fingerprinting operations."
  [monolith subprojects]
  (let [dep-map (dep/dependency-map subprojects)
        initial (read-fingerprints-file monolith)
        cache (atom {})]
    {:monolith monolith
     :subprojects subprojects
     :dependencies dep-map
     :initial initial
     :cache cache}))


(defn- fingerprints
  "Returns a map of fingerpints associated with a project, including the ::final
  one. Can be compared with a previous fingerprint file."
  [ctx project-name]
  (let [{:keys [subprojects dependencies cache]} ctx]
    (hash-inputs (subprojects project-name) dependencies subprojects cache)))


(defn changed?
  "Determines if a project has changed since the last fingerprint saved under the
  given marker."
  [ctx marker project-name]
  (let [{:keys [initial]} ctx
        current (fingerprints ctx project-name)
        past (get-in initial [marker project-name])]
    (not= (::final past) (::final current))))


(defn- explain-kw
  [ctx marker project-name]
  (let [{:keys [initial]} ctx
        current (fingerprints ctx project-name)
        past (get-in initial [marker project-name])]
    (cond
      (nil? past) ::new-project

      (= (::final past) (::final current)) ::up-to-date

      :else
      (or (some
            (fn [ftype]
              (when (not= (ftype past) (ftype current))
                ftype))
            [::sources ::deps ::upstream])
          ::unknown))))


(def ^:private reason-details
  {::up-to-date ["is up-to-date" "are up-to-date" :green]
   ::new-project ["is a new project" "are new projects" :red]
   ::sources ["has updated sources" "have updated sources" :red]
   ::deps ["has updated external dependencies" "have updated external dependencies" :yellow]
   ::upstream ["is downstream of an affected project" "are downstream of affected projects" :yellow]
   ::unknown ["has a different fingerprint" "have different fingerprints" :red]})


(defn explain-str
  [ctx marker project-name]
  (let [[singular plural color] (reason-details (explain-kw ctx marker project-name))]
    (ansi/sgr singular color)))


(defn save!
  "Save the fingerprints for a project with the specified marker."
  [ctx marker project-name]
  (let [current (fingerprints ctx project-name)]
    (update-fingerprints-file!
      (:monolith ctx)
      assoc-in [marker project-name] current)))


(defn- list-projects
  [project-names color]
  (->> project-names
       (map #(ansi/sgr % color))
       (str/join ", ")))


(defn changed
  [project opts markers]
  (let [[monolith subprojects] (u/load-monolith! project)
        ctx (context monolith subprojects)
        targets (filter subprojects (target/select monolith subprojects opts))
        markers (if (seq markers)
                  markers
                  (keys (:initial ctx)))]
    (cond
      (empty? markers) (lein/info "No saved fingerprint markers")
      (empty? targets) (lein/info "No projects selected")

      :else
      (doseq [marker markers
              :let [changed (->> targets
                                 (filter (partial changed? ctx marker))
                                 (set))
                    pct-changed (if (seq targets)
                                  (* 100.0 (/ (count changed) (count targets)))
                                  0.0)]]
        (lein/info (ansi/sgr (format "%.2f%%" pct-changed)
                             (cond
                               (== 0.0 pct-changed) :green
                               (< pct-changed 50) :yellow
                               :else :red))
                   "out of"
                   (count targets)
                   "projects have out-of-date"
                   (ansi/sgr marker :bold)
                   "fingerprints:\n")
        (let [reasons (group-by (partial explain-kw ctx marker) targets)]
          (doseq [k [::unknown ::new-project ::sources ::resources ::deps ::upstream ::up-to-date]]
            (when-let [projs (seq (k reasons))]
              (let [[singular plural color] (reason-details k)
                    c (count projs)]
                (lein/info "*" (ansi/sgr (count projs) color)
                           (str (if (= 1 c) singular plural)
                                (when-not (#{::up-to-date ::upstream} k)
                                  (str ": " (list-projects projs color)))))))))
        (when (seq (rest markers))
          (lein/info))))))


(defn mark
  [project opts markers]
  (when-not (seq markers)
    (lein/abort "Please specify one or more markers!"))
  (let [[monolith subprojects] (u/load-monolith! project)
        ctx (context monolith subprojects)
        targets (filter subprojects (target/select monolith subprojects opts))
        fprints (->> targets
                     (map
                       (fn [project-name]
                         [project-name (fingerprints ctx project-name)]))
                     (into {}))]
    (update-fingerprints-file!
      monolith
      (fn add-new-fingerprints
        [all-fprints]
        (reduce
          #(update %1 %2 merge fprints)
          all-fprints
          markers)))
    (lein/info (format "Set %s markers for %s projects"
                       (ansi/sgr (count markers) :bold)
                       (ansi/sgr (count targets) :bold)))))


(defn clear
  [project opts markers]
  (let [[monolith subprojects] (u/load-monolith! project)
        markers (set markers)
        targets (set (filter subprojects (target/select monolith subprojects opts)))]
    (update-fingerprints-file!
      monolith
      (partial into {}
               (keep
                 (fn [[marker fprints]]
                   (if (markers marker)
                     (when-let [fprints' (seq (filter (comp targets val) fprints))]
                       [marker fprints'])
                     [marker fprints])))))
    (lein/info (format "Cleared %s markers for %s projects"
                       (ansi/sgr (count markers) :bold)
                       (ansi/sgr (count targets) :bold)))))
