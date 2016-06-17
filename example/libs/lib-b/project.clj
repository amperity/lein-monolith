(defproject example/lib-b "0.1.0-SNAPSHOT"
  :description "Example lib depending on lib-a."

  :dependencies
  [[example/lib-a "1.0.0"]
   [org.clojure/clojure "1.8.0"]])
