(defproject lein-monolith.example/lib-d "MONOLITH-SNAPSHOT"
  :description "Example lib depending on lib-b, for testing transitive deps-on."
  :monolith/inherit [:aliases]

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [lein-monolith.example/lib-b "MONOLITH-SNAPSHOT"]])
