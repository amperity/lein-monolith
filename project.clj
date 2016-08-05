(defproject lein-monolith "0.2.2-SNAPSHOT"
  :description "Leiningen plugin for managing subrojects within a monorepo."
  :url "https://github.com/amperity/lein-monolith"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]

  :eval-in-leiningen true

  :dependencies
  [[mvxcvi/puget "1.0.0"]
   [rhizome "0.2.7"]]

  :profiles
  {:dev {:plugins [[rfkm/lein-cloverage "1.0.8"]]
         :dependencies [[org.clojure/clojure "1.8.0"]]}})
