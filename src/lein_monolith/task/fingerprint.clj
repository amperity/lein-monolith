(ns lein-monolith.task.fingerprint
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [lein-monolith.color :refer [colorize]]
    [lein-monolith.dependency :as dep]
    [lein-monolith.plugin :as plugin]
    [lein-monolith.target :as target]
    [lein-monolith.task.util :as u]
    [leiningen.core.main :as lein])
  (:import
    (java.io
      File
      InputStream)
    java.security.MessageDigest
    java.util.Base64))


;; ## Options

(def task-opts
  (assoc target/selection-opts
         :upstream 0
         :downstream 0
         :debug 0))


;; ## Hashing projects' inputs

(defn- base64
  [^bytes content]
  (-> (Base64/getUrlEncoder)
      (.withoutPadding)
      (.encode content)
      (String.)))


(defn- sha1
  "Takes a string or an InputStream, and returns a base64 string representing the
  SHA-1 hash."
  [content]
  (let [hasher (MessageDigest/getInstance "SHA-1")]
    (cond
      (string? content)
      (.update hasher (.getBytes ^String content))

      (instance? InputStream content)
      (let [buffer (byte-array 4096)
            in ^InputStream content]
        (loop []
          (let [n (.read in buffer 0 (alength buffer))]
            (when (pos? n)
              (.update hasher buffer 0 n)
              (recur)))))

      :else
      (throw (ex-info
               (str "Cannot compute digest from " (type content))
               {})))
    (base64 (.digest hasher))))


(defn- kv-hash
  "Takes a map from strings (ids of things we hashed) to strings (their hash
  results); returns a new hash that identifies the aggregate
  collection."
  [kind m]
  {:pre [(every? string? (vals m))]}
  (->> (seq m)
       (sort-by key)
       (map #(str (key %) \tab (val %)))
       (str/join "\n")
       (str "v2/" (name kind) "\n")
       (sha1)))


(defn- list-all-files
  [^File file]
  (if (.isFile file)
    [file]
    (mapcat list-all-files (.listFiles file))))


(defn- local-path
  [project ^File file]
  (let [root (:root project)
        path (.getAbsolutePath file)]
    (when-not (str/starts-with? path root)
      (throw (ex-info "Cannot determine local path with different root" {})))
    (subs path (count root))))


(defn- all-paths
  "Finds all source, test, and resource paths associated with a project, including
  those set in profiles."
  [project]
  (->> (concat
         [project]
         (vals (:profiles project)))
       (mapcat (juxt :source-paths :java-source-paths :test-paths :resource-paths))
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
       (map (fn hash-file
              [^File file]
              [(local-path project file)
               (with-open [in (io/input-stream file)]
                 (sha1 in))]))
       (into {})
       (kv-hash :files)))


(defn- dependency-coordinate-map
  "Turn a sequence of dependency vectors into a map from dependency symbols to
  coordinate maps, with the version in `:version` and other qualifiers added as
  entries."
  [dependencies]
  (into (sorted-map)
        (map
          (fn dep-entry
            [[dep-sym version & {:as extra}]]
            [(if (namespace dep-sym)
               dep-sym
               (symbol (name dep-sym) (name dep-sym)))
             (assoc extra :version version)]))
        dependencies))


(defn- hash-dependency-coordinate
  "Hash a single dependency coordinate."
  [coordinate]
  (kv-hash
    :dep-coordinate
    (into {}
          (map (juxt key (comp pr-str val)))
          coordinate)))


(defn- hash-profile-dependencies
  "Given a map of profiles, construct a hash of profile name/dependency type
  keys to hashes."
  [profiles]
  (into {}
        (comp
          (mapcat
            (fn lookup-deps
              [[prof-key profile]]
              [(when-let [deps (seq (:dependencies profile))]
                 [prof-key :dependencies (dependency-coordinate-map deps)])
               (when-let [deps (seq (:managed-dependencies profile))]
                 [prof-key :managed-dependencies (dependency-coordinate-map deps)])]))
          (remove nil?)
          (map
            (fn hash-deps
              [[prof-key dep-key deps]]
              [(str prof-key \tab dep-key)
               (->> deps
                    (into {} (map (juxt key (comp hash-dependency-coordinate val))))
                    (kv-hash :profile-dependencies))])))
        profiles))


(defn- hash-dependencies
  "Hashes a project's dependencies and managed dependencies, as well as that of
  its profiles and project root."
  [project]
  (->> (assoc (:profiles project) ::default project)
       (hash-profile-dependencies)
       (map #(str (key %) \tab (val %)))
       (sort)
       (str/join "\n")
       (str "v3/dependencies\n")
       (sha1)))


(declare hash-inputs)


(defn- hash-upstream-projects
  [project dep-map subprojects cache]
  (into (sorted-map)
        (keep
          (fn hash-upstream
            [subproject-name]
            (when-let [subproject (subprojects subproject-name)]
              [subproject-name
               (::final (hash-inputs subproject dep-map subprojects cache))])))
        (dep-map (dep/project-name project))))


(defn- hash-jar-exclusions
  [project]
  (kv-hash
    :jar-exclusions
    (into
      {}
      (map (fn [[profile-key profile]]
             [profile-key (pr-str (:jar-exclusions profile))]))
      (assoc (:profiles project) ::default project))))


(defn- hash-inputs
  "Hashes each of a project's inputs, and returns a map containing each individual
  result, so it's easier to explain what aspect of a project caused its overall
  fingerprint to change.

  Returns a map of `{::xyz \"hash\"}`

  Keeps a cache of hashes computed so far, for efficiency."
  [project dep-map subprojects cache]
  (let [project-name (dep/project-name project)]
    (or (@cache project-name)
        (let [upstream-hashes
              (hash-upstream-projects project dep-map subprojects cache)

              prints
              {::version (str (:version project))
               ::java-version (System/getProperty "java.version")
               ::jar-exclusions (hash-jar-exclusions project)
               ::seed (str (:monolith/fingerprint-seed project 0))
               ::sources (hash-sources project)
               ::deps (hash-dependencies project)
               ::upstream (kv-hash :projects upstream-hashes)}

              prints
              (assoc prints
                     ::upstream-hashes upstream-hashes
                     ::final (kv-hash :inputs prints)
                     ::time (System/currentTimeMillis))]
          (swap! cache assoc project-name prints)
          prints))))


;; ## Storing fingerprints

;; The .lein-monolith-fingerprints file at the metaproject root stores the
;; detailed fingerprint map for each project and marker type.

(comment
  ;; Example .lein-monolith-fingerprints
  {"build" {'foo/bar {::sources "abcde"
                      ,,,
                      ::final "vwxyz"}
            ,,,}
   ,,,})


(defn- fingerprints-file
  ^File
  [root]
  (io/file root ".lein-monolith-fingerprints"))


(defn- read-fingerprints-file
  [root]
  (let [f (fingerprints-file root)]
    (when (.exists f)
      (edn/read-string (slurp f)))))


(defn- write-fingerprints-file!
  [root fingerprints]
  (let [file (fingerprints-file root)]
    (spit file (pr-str fingerprints))))


(let [lock (Object.)]
  (defn update-fingerprints-file!
    [root f & args]
    (locking lock
      (write-fingerprints-file!
        root
        (apply f (read-fingerprints-file root) args)))))


;; ## Generating and comparing fingerprints

(defn context
  "Create a stateful context to use for fingerprinting operations."
  [monolith subprojects]
  (let [dep-map (dep/dependency-map subprojects)
        root (:root monolith)
        initial (read-fingerprints-file root)
        cache (atom {})
        subprojects (into {}
                          (map
                            (fn inherit-profiles
                              [[k subproject]]
                              [k
                               (update subproject :profiles merge
                                       (into {}
                                             (plugin/build-profiles
                                               monolith subproject)))]))
                          subprojects)]
    {:root root
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
  (let [current (fingerprints ctx project-name)
        past (get-in ctx [:initial marker project-name])]
    (not= (::final past) (::final current))))


(def ^:private fingerprint-priority
  "Priority ordered list of fingerprints to check; keys appearing earlier in
  the list will take precedence when explaining why a project is considered
  changed."
  [::version
   ::seed
   ::sources
   ::deps
   ::java-version
   ::jar-exclusions
   ::upstream])


(def ^:private reason-details
  {::up-to-date ["is up-to-date" "are up-to-date" :green]
   ::new-project ["is a new project" "are new projects" :red]
   ::version ["has a different version" "have different versions" :red]
   ::java-version ["has a different java version" "have different java versions" :red]
   ::jar-exclusions ["has different JAR exclusions" "have different JAR exclusions" :yellow]
   ::seed ["has a different seed" "have different seeds" :yellow]
   ::sources ["has updated sources" "have updated sources" :red]
   ::deps ["has updated external dependencies" "have updated external dependencies" :yellow]
   ::upstream ["is downstream of an affected project" "are downstream of affected projects" :yellow]
   ::unknown ["has a different fingerprint" "have different fingerprints" :red]})


(defn- explain-kw
  [ctx marker project-name]
  (let [current (fingerprints ctx project-name)
        past (get-in ctx [:initial marker project-name])]
    (cond
      (nil? past)
      ::new-project

      (= (::final past) (::final current))
      ::up-to-date

      :else
      (or (some
            (fn [ftype]
              (when (not= (ftype past) (ftype current))
                ftype))
            fingerprint-priority)
          ::unknown))))


(defn explain-str
  [ctx marker project-name]
  (let [[singular _ color] (reason-details (explain-kw ctx marker project-name))]
    (colorize color singular)))


(defn save!
  "Save the fingerprints for a project with the specified marker."
  [ctx marker project-name]
  (let [current (fingerprints ctx project-name)]
    (update-fingerprints-file!
      (:root ctx) assoc-in [marker project-name] current)))


(defn- list-projects
  [project-names color]
  (->> project-names
       (map (partial colorize color))
       (str/join ", ")))


(defn- debug-project-fingerprints
  "Print a detailed representation of a project's fingerprints for debugging."
  [project-name past current]
  (let [all-attrs (disj (into (sorted-set)
                              (concat (keys past)
                                      (keys current)))
                        ::upstream-hashes
                        ::final
                        ::time)
        ordered-attrs (into []
                            (filter all-attrs)
                            fingerprint-priority)
        compare-attrs (concat
                        ordered-attrs
                        (sort (remove (set ordered-attrs) all-attrs))
                        [::final])
        render-attr (fn render-attr
                      [old-val new-val same-color]
                      (cond
                        (and (nil? old-val) new-val)
                        (colorize :green new-val)

                        (and old-val (nil? new-val))
                        (colorize :red old-val)

                        (= old-val new-val)
                        (if same-color
                          (colorize same-color new-val)
                          new-val)

                        :else
                        (str (colorize :red old-val)
                             " => "
                             (colorize :green new-val))))]
    (println project-name)
    (doseq [attr compare-attrs]
      (printf "%24s: %s\n"
              (colorize :cyan (name attr))
              (render-attr (get past attr)
                           (get current attr)
                           nil))
      (when (= ::upstream attr)
        (let [past-map (::upstream-hashes past)
              curr-map (::upstream-hashes current)]
          (doseq [upstream (into (sorted-set)
                                 (concat (keys past-map)
                                         (keys curr-map)))]
            (printf "%16s * %s: %s\n"
                    " " upstream
                    (render-attr (get past-map upstream)
                                 (get curr-map upstream)
                                 :yellow)))))))
  (newline)
  (flush))


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
        (lein/info (colorize
                     (cond
                       (== 0.0 pct-changed) :green
                       (< pct-changed 50) :yellow
                       :else :red)
                     (format "%.2f%%" pct-changed))
                   "out of"
                   (count targets)
                   "projects have out-of-date"
                   (colorize :bold marker)
                   "fingerprints:\n")
        (let [reasons (group-by (partial explain-kw ctx marker) targets)]
          (doseq [k (concat [::unknown
                             ::new-project]
                            fingerprint-priority
                            [::up-to-date])]
            (when-let [projs (seq (k reasons))]
              (let [[singular plural color] (reason-details k)
                    c (count projs)]
                (lein/info "*" (colorize color (count projs))
                           (str (if (= 1 c) singular plural)
                                (when-not (#{::up-to-date ::upstream} k)
                                  (str ": " (list-projects projs color)))))
                (when (and (:debug opts)
                           (not= k ::new-project)
                           (not= k ::up-to-date))
                  (doseq [project-name projs]
                    (debug-project-fingerprints
                      project-name
                      (get-in ctx [:initial marker project-name])
                      (fingerprints ctx project-name))))))))
        (lein/info)))))


(defn mark-fresh
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
      (:root monolith)
      (fn add-new-fingerprints
        [all-fprints]
        (reduce
          #(update %1 %2 merge fprints)
          all-fprints
          markers)))
    (lein/info (format "Set %s markers for %s projects"
                       (colorize :bold (count markers))
                       (colorize :bold (count targets))))))


(defn show
  [project marker targets]
  (when-not (seq targets)
    (lein/abort "Please specify at least one project to show"))
  (let [[monolith subprojects] (u/load-monolith! project)
        ctx (context monolith subprojects)
        projects (->> {:in (set targets)}
                      (target/select monolith subprojects)
                      (dep/topological-sort (dep/dependency-map subprojects)))]
    (doseq [project-name projects]
      (debug-project-fingerprints
        project-name
        (get-in ctx [:initial marker project-name])
        (fingerprints ctx project-name)))))


(defn clear
  [project opts markers]
  (let [[monolith subprojects] (u/load-monolith! project)
        ctx (context monolith subprojects)
        markers (if (seq markers)
                  (set markers)
                  (set (keys (:initial ctx))))
        targets (set (filter subprojects (target/select monolith subprojects opts)))]
    (update-fingerprints-file!
      (:root monolith)
      (partial into {}
               (keep
                 (fn [[marker fprints]]
                   (if (markers marker)
                     (when-let [fprints' (seq (filter (comp targets val) fprints))]
                       [marker fprints'])
                     [marker fprints])))))
    (lein/info (format "Cleared %s markers for %s projects"
                       (colorize :bold (count markers))
                       (colorize :bold (count targets))))))
