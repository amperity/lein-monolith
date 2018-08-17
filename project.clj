(defproject lein-monolith "1.1.1-SNAPSHOT"
  :description "Leiningen plugin for managing subrojects within a monorepo."
  :url "https://github.com/amperity/lein-monolith"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :eval-in-leiningen true
  :deploy-branches ["master"]

  :dependencies
  [[manifold "0.1.8"]
   [mvxcvi/puget "1.0.2"]
   [rhizome "0.2.9"]]

  :hiera
  {:vertical false
   :show-external true
   :cluster-depth 1
   :ignore-ns #{clojure puget manifold}}

  :profiles
  {:dev {:plugins [[lein-cloverage "1.0.9"]]
         :dependencies [[org.clojure/clojure "1.9.0"]]}})
