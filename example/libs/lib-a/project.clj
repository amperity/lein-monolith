(defproject lein-monolith.example/lib-a "MONOLITH-SNAPSHOT"
  :description "Example library with no internal dependencies."
  :monolith/inherit true

  :dependencies
  [[org.clojure/clojure "1.10.1"]])
