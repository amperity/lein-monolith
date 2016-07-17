(defproject example/all "MONOLITH"
  :description "Overarching example project."

  :plugins
  [[lein-monolith "0.1.0"]]

  :dependencies
  [[org.clojure/clojure "1.8.0"]]

  :monolith
  {:project-dirs
   ["apps/app-a" "libs/*" "not-found"]})
