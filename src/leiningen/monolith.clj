(ns leiningen.monolith
  (:require
    [clojure.java.io :as jio]
    [clojure.pprint :refer [pprint]])
  (:import
    (java.io
      File
      PushbackReader)))


(def config-name "monolith.clj")


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
     (-> (jio/reader file)
         (PushbackReader.)
         (read)
         (vary-meta assoc ::config-path (str file))))))


(defn- merged-profile
  "Constructs a profile map containing merged `:src-paths` and `:test-paths` entries."
  [version options]
  {:src-paths ['...]
   :test-paths ['...]
   :dependencies ['...]})



;; ## Command Implementations

(defn- print-help
  []
  (println "NYI"))


(defn- print-stats
  [project args]
  (let [config (load-config!)]
    (println "Config path:" (::config-path (meta config)))
    (println "Configuration:")
    (pprint config)))


(defn- link-checkouts!
  [project args]
  ; ...
  (println "NYI"))


(defn- check-dependencies
  [project args]
  ; ...
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
    (nil "stats")
      (print-stats project args)

    "checkouts"
      (link-checkouts! project args)

    "deps"
      (check-dependencies project args)

    "with-all"
      (apply-with-all project args)

    "help"
      (print-help)

    (do
      (println (pr-str command) "is not a valid monolith command!")
      (System/exit 1))))
