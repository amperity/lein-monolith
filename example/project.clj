(defproject example/all "MONOLITH"
  :description "Overarching example project."

  :aliases
  {"version+" ["version"]
   "version++" ["version+"]}

  :plugins
  [[lein-monolith "1.5.1-SNAPSHOT"]
   [lein-pprint "1.2.0"]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]]

  :test-selectors
  {:unit (complement :integration)
   :integration :integration}

  :monolith
  {:inherit
   [:aliases
    :test-selectors
    :env]

   :inherit-leaky
   [:repositories
    :managed-dependencies]

   :project-selectors
   {:deployable :deployable
    :unstable #(= (first (:version %)) \0)}

   :project-dirs
   ["apps/app-a"
    "libs/*"
    "not-found"]}

  :env
  {:foo "bar"})
