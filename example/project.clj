(defproject example/all "MONOLITH"
  :description "Overarching example project."

  :plugins
  [[lein-monolith "0.1.2-SNAPSHOT"]]

  :dependencies
  [[org.clojure/clojure "1.8.0"]]

  :test-selectors
  {:unit (complement :integration)
   :integration :integration}

  :monolith
  {:inherit
   [:repositories
    :test-selectors
    :managed-dependencies]

   :project-dirs
   ["apps/app-a"
    "libs/*"
    "not-found"]})
