(defproject example/lib-b "MONOLITH-SNAPSHOT"
  :description "Example lib depending on lib-a."

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [example/lib-a "MONOLITH-SNAPSHOT"]])
