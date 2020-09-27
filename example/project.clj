(defproject lein-monolith/example.all "MONOLITH"
  :description "Overarching example project."

  :plugins
  [[lein-monolith "1.5.1-SNAPSHOT"]
   [lein-pprint "1.2.0"]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]]

  :test-selectors
  {:unit (complement :integration)
   :integration :integration}

  :test-paths ^:replace
  ["test/unit"
   "test/integration"]

  :monolith
  {:inherit
   [:test-selectors
    :env]

   :inherit-raw
   [:test-paths]

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
