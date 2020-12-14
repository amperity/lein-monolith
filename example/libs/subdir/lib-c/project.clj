(defproject lein-monolith.example/lib-c "MONOLITH-SNAPSHOT"
  :description "Example lib depending on lib-a."
  :monolith/inherit [:aliases]

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [lein-monolith.example/lib-a "MONOLITH-SNAPSHOT"]])
