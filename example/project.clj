(defproject example/all "MONOLITH"
  :description "Overarching example project."

  :plugins
  [[lein-monolith "0.2.0-SNAPSHOT"]
   [lein-cprint "1.2.0"]]

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
