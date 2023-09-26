(defproject lein-monolith "1.8.0"
  :description "Leiningen plugin for managing subrojects within a monorepo."
  :url "https://github.com/amperity/lein-monolith"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :eval-in-leiningen true
  :deploy-branches ["main"]

  :dependencies
  [[manifold "0.2.4"]
   [rhizome "0.2.9"]]

  :hiera
  {:vertical false
   :show-external true
   :cluster-depth 1
   :ignore-ns #{clojure manifold}}

  :profiles
  {:dev
   {:plugins [[lein-cloverage "1.2.4"]]
    :dependencies [[org.clojure/clojure "1.10.3"]]}

   :ci
   {:plugins [[test2junit "1.4.2"]]
    :test2junit-silent true
    :test2junit-output-dir "test-results/clojure.test"}})
