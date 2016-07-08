(defproject lein-monolith "0.1.1"
  :description "Leiningen plugin for managing subrojects within a monorepo."
  :url "https://github.com/amperity/lein-monolith"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]

  :eval-in-leiningen true

  :dependencies
  [[mvxcvi/puget "1.0.0"]
   [rhizome "0.2.5"]])
