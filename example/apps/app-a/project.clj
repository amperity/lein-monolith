(defproject lein-monolith.example/app-a "MONOLITH-SNAPSHOT"
  :description "Example project with internal and external dependencies."
  :monolith/inherit true
  :monolith/dependency-set :set-a
  :deployable true

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [commons-io "2.5"]
   [lein-monolith.example/lib-a "MONOLITH-SNAPSHOT"]
   [lein-monolith.example/lib-b "MONOLITH-SNAPSHOT"]]

  :profiles
  {:shared {:source-paths ["bench"]}
   :dev [:shared {:dependencies [[clj-stacktrace "0.2.8"]]}]
   :uberjar [:shared {:dependencies [[commons-net "3.6"]]}]})
