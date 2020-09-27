(defproject lein-monolith/example.lib-a "1.0.0"
  :description "Example library with no internal dependencies."
  :monolith/inherit true

  :dependencies
  [[org.clojure/clojure "1.8.0"]])
